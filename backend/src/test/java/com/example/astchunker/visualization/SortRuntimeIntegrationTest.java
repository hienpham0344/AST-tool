package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.BreakpointMapper;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.ExecutionObservation;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises recognized sort patterns against multiple independent runtime inputs. */
class SortRuntimeIntegrationTest {
  private static final String BUBBLE =
      """
      for (int i = 0; i < nums.length - 1; i++) {
        for (int j = 0; j < nums.length - 1 - i; j++) {
          if (nums[j] > nums[j + 1]) {
            int temp = nums[j];
            nums[j] = nums[j + 1];
            nums[j + 1] = temp;
          }
        }
      }
      """;
  private static final String SELECTION =
      """
      for (int i = 0; i < nums.length - 1; i++) {
        int min = i;
        for (int j = i + 1; j < nums.length; j++) {
          if (nums[j] < nums[min]) min = j;
        }
        int temp = nums[i];
        nums[i] = nums[min];
        nums[min] = temp;
      }
      """;
  private static final String INSERTION =
      """
      for (int i = 1; i < nums.length; i++) {
        int key = nums[i];
        int j = i - 1;
        while (j >= 0 && nums[j] > key) {
          nums[j + 1] = nums[j];
          j--;
        }
        nums[j + 1] = key;
      }
      """;

  private static final List<List<Integer>> EXPECTED =
      List.of(
          List.of(),
          List.of(7),
          List.of(1, 2, 3),
          List.of(1, 2, 3),
          List.of(1, 2, 2, 4, 4),
          List.of(-3, -1, 0, 2));

  record Case(String name, String algorithm, String type) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Case> cases() {
    return Stream.of(
        new Case("bubble", BUBBLE, "bubble-sort"),
        new Case("selection", SELECTION, "selection-sort"),
        new Case("insertion", INSERTION, "insertion-sort"));
  }

  private List<ExecutionObservation> run(String input, String algorithm) {
    String source =
        "public class SortSample {\npublic static void main(String[] args) {\n"
            + "int[] nums = {"
            + input
            + "};\n"
            + algorithm
            + "System.out.println(java.util.Arrays.toString(nums));\n}\n}";
    return runSource(source);
  }

  private List<ExecutionObservation> runAllCases(String algorithm) {
    String source =
        """
        public class SortSample {
          public static void main(String[] args) {
            int[][] inputs = {{}, {7}, {1, 2, 3}, {3, 2, 1}, {4, 2, 4, 1, 2}, {0, -3, 2, -1}};
            for (int[] input : inputs) {
              int[] nums = input.clone();
              sort(nums);
            }
          }
          private static void sort(int[] nums) {
        """
            + algorithm
            + "\nSystem.out.println(java.util.Arrays.toString(nums));\n  }\n}";
    return runSource(source);
  }

  private List<ExecutionObservation> runSource(String source) {
    var analysis = new AstAnalyzer().analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints).hasSize(1);
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var trace =
          new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
              .observe(target, analysis);
      assertThat(trace.warnings()).noneMatch(w -> w.contains("timed out"));
      assertThat(trace.executions()).isNotEmpty();
      return new VisualTraceBuilder().build(analysis, hints, trace.executions());
    }
  }

  private Object array(ExecutionObservation step) {
    return step.variables().stream()
        .filter(v -> v.variableName().equals("nums"))
        .findFirst()
        .orElseThrow()
        .visualValue();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void sortsMultipleArraysDuringOneTargetRun(Case sample) {
    var steps = runAllCases(sample.algorithm());
    var endings = steps.stream().filter(s -> s.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(EXPECTED.size());
    assertThat(endings).extracting(this::array).containsExactlyElementsOf(EXPECTED);
    assertThat(endings)
        .allSatisfy(
            end -> {
              assertThat(end.snapshotPhase()).isEqualTo("BEFORE_LOCATION");
              assertThat(end.visualStates()).hasSize(1);
              assertThat(end.visualStates().get(0).algorithm()).isEqualTo(sample.type());
              assertThat(end.visualStates().get(0).sortFrame().sortedRegion().status())
                  .isEqualTo("VERIFIED_SORTED");
            });
  }

  @Test
  void bubbleSwapHasIntermediateDuplicateValueNotAnAtomicSwap() {
    var steps = run("2,1", BUBBLE);
    var secondWrite =
        steps.stream()
            .filter(s -> s.code().equals("nums[j + 1] = temp;"))
            .findFirst()
            .orElseThrow();
    assertThat(array(secondWrite)).isEqualTo(List.of(1, 1));
    assertThat(secondWrite.variables())
        .anySatisfy(
            v -> {
              assertThat(v.variableName()).isEqualTo("temp");
              assertThat(v.visualValue()).isEqualTo(2);
            });
  }

  @Test
  void supportsDescendingBubbleRuntime() {
    var steps = run("1,3,2", BUBBLE.replace("nums[j] >", "nums[j] <"));
    var end =
        steps.stream()
            .filter(s -> s.code().startsWith("System.out.println"))
            .findFirst()
            .orElseThrow();
    assertThat(array(end)).isEqualTo(List.of(3, 2, 1));
    assertThat(end.visualStates().get(0).sortFrame().sortedRegion().status())
        .isEqualTo("VERIFIED_SORTED");
  }

  @Test
  void supportsDescendingSelectionRuntime() {
    String descending = SELECTION.replace("nums[j] < nums[min]", "nums[j] > nums[min]");
    var steps = run("1,3,2", descending);
    var end =
        steps.stream()
            .filter(s -> s.code().startsWith("System.out.println"))
            .findFirst()
            .orElseThrow();
    assertThat(array(end)).isEqualTo(List.of(3, 2, 1));
    assertThat(end.visualStates().get(0).sortFrame().sortedRegion().status())
        .isEqualTo("VERIFIED_SORTED");
  }

  @Test
  void supportsDescendingInsertionRuntime() {
    String descending = INSERTION.replace("nums[j] > key", "nums[j] < key");
    var steps = run("1,3,2", descending);
    var end =
        steps.stream()
            .filter(s -> s.code().startsWith("System.out.println"))
            .findFirst()
            .orElseThrow();
    assertThat(array(end)).isEqualTo(List.of(3, 2, 1));
    assertThat(end.visualStates().get(0).sortFrame().sortedRegion().status())
        .isEqualTo("VERIFIED_SORTED");
  }

  @Test
  void leavesHelperBubbleAndAdjacentInsertionAsGenericRuntimeObservations() {
    for (String source : List.of(helperBubbleProgram(), adjacentInsertionProgram())) {
      var analysis = new AstAnalyzer().analyze(source);
      assertThat(new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit()))
          .singleElement()
          .satisfies(hint -> assertThat(hint.sort()).isNull());

      var steps = runSource(source);
      var states = steps.stream().flatMap(step -> step.visualStates().stream()).toList();

      assertThat(states).isNotEmpty();
      assertThat(states)
          .allSatisfy(
              state ->
                  assertThat(state.sortFrame())
                      .as("generic states must not expose synthetic pending or completed swaps")
                      .isNull());
      assertThat(steps)
          .filteredOn(step -> step.code().startsWith("System.out.println"))
          .extracting(this::array)
          .containsExactly(List.of(1, 2, 3));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"byte", "short", "long", "char", "float", "double"})
  void primitiveNumericBubbleRunsWithoutSyntheticSortFrames(String type) {
    String values = switch (type) {
      case "char" -> "'c', 'a', 'b'";
      case "float" -> "3.0f, 1.0f, 2.0f";
      case "double" -> "3.0, 1.0, 2.0";
      default -> "3, 1, 2";
    };
    String source = "public class SortSample { public static void main(String[] args) {\n"
        + type + "[] nums = {" + values + "};\n"
        + BUBBLE.replace("int temp", type + " temp")
        + "System.out.println(java.util.Arrays.toString(nums));\n} }";
    var analysis = new AstAnalyzer().analyze(source);
    assertThat(new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit()))
        .singleElement()
        .satisfies(hint -> {
          assertThat(hint.type()).isEqualTo("bubble-sort");
          assertThat(hint.sort()).isNull();
        });
    var steps = runSource(source);
    var states = steps.stream().flatMap(step -> step.visualStates().stream()).toList();
    assertThat(states).isNotEmpty();
    assertThat(states).allSatisfy(state -> assertThat(state.sortFrame()).isNull());
    Object expected = switch (type) {
      case "char" -> List.of("a", "b", "c");
      case "float", "double" -> List.of(1.0, 2.0, 3.0);
      default -> List.of(1, 2, 3);
    };
    assertThat(steps)
        .filteredOn(step -> step.code().startsWith("System.out.println"))
        .extracting(this::array)
        .containsExactly(expected);
  }

  @Test
  void referenceArrayComparatorBubbleRunsWithoutSyntheticSortFrames() {
    String source =
        """
        public class SortSample {
          private static void sort(String[] nums, java.util.Comparator<String> comparator) {
            for (int pass = 0; pass < nums.length - 1; pass++) {
              for (int scan = 0; scan < nums.length - 1 - pass; scan++) {
                if (comparator.compare(nums[scan], nums[scan + 1]) > 0) {
                  String temp = nums[scan];
                  nums[scan] = nums[scan + 1];
                  nums[scan + 1] = temp;
                }
              }
            }
          }
          public static void main(String[] args) {
            String[] nums = {"c", "a", "b"};
            sort(nums, java.util.Comparator.naturalOrder());
            System.out.println(java.util.Arrays.toString(nums));
          }
        }
        """;
    var analysis = new AstAnalyzer().analyze(source);
    assertThat(new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit()))
        .singleElement()
        .satisfies(hint -> {
          assertThat(hint.type()).isEqualTo("bubble-sort");
          assertThat(hint.sort()).isNull();
        });

    var steps = runSource(source);
    var states = steps.stream().flatMap(step -> step.visualStates().stream()).toList();
    assertThat(states).isNotEmpty();
    assertThat(states).allSatisfy(state -> assertThat(state.sortFrame()).isNull());
    assertThat(steps)
        .filteredOn(step -> step.code().startsWith("System.out.println"))
        .extracting(this::array)
        .containsExactly(List.of("a", "b", "c"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"java.lang.String", "String"})
  void jdkStringNaturalBubbleRunsWithoutSyntheticSortFrames(String elementType) {
    String source =
        """
        public class SortSample {
          private static void sort(%s[] nums) {
            for (int pass = 0; pass < nums.length - 1; pass++) {
              for (int scan = 0; scan < nums.length - 1 - pass; scan++) {
                if (nums[scan].compareTo(nums[scan + 1]) > 0) {
                  %s temp = nums[scan];
                  nums[scan] = nums[scan + 1];
                  nums[scan + 1] = temp;
                }
              }
            }
          }
          public static void main(String[] args) {
            java.lang.String[] nums = {"c", "a", "b"};
            sort(nums);
            System.out.println(java.util.Arrays.toString(nums));
          }
        }
        """.formatted(elementType, elementType);
    var analysis = new AstAnalyzer().analyze(source);
    assertThat(new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit()))
        .singleElement()
        .satisfies(hint -> {
          assertThat(hint.type()).isEqualTo("bubble-sort");
          assertThat(hint.sort()).isNull();
        });

    var steps = runSource(source);
    var states = steps.stream().flatMap(step -> step.visualStates().stream()).toList();
    assertThat(states).isNotEmpty();
    assertThat(states).allSatisfy(state -> assertThat(state.sortFrame()).isNull());
    assertThat(steps)
        .filteredOn(step -> step.code().startsWith("System.out.println"))
        .extracting(this::array)
        .containsExactly(List.of("a", "b", "c"));
  }

  @Test
  void shortIntegerNaturalBubbleRunsWithoutSyntheticSortFrames() {
    String source = """
        public class SortSample {
          private static void sort(Integer[] nums) {
            for (int pass = 0; pass < nums.length - 1; pass++) {
              for (int scan = 0; scan < nums.length - 1 - pass; scan++) {
                if (nums[scan].compareTo(nums[scan + 1]) > 0) {
                  Integer temp = nums[scan];
                  nums[scan] = nums[scan + 1];
                  nums[scan + 1] = temp;
                }
              }
            }
          }
          public static void main(String[] args) {
            Integer[] nums = {3, 1, 2};
            sort(nums);
            System.out.println(java.util.Arrays.toString(nums));
          }
        }
        """;
    var analysis = new AstAnalyzer().analyze(source);
    assertThat(new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit()))
        .singleElement().satisfies(hint -> {
          assertThat(hint.type()).isEqualTo("bubble-sort");
          assertThat(hint.sort()).isNull();
        });

    var steps = runSource(source);
    var states = steps.stream().flatMap(step -> step.visualStates().stream()).toList();
    assertThat(states).isNotEmpty();
    assertThat(states).allSatisfy(state -> assertThat(state.sortFrame()).isNull());
    assertThat(steps)
        .filteredOn(step -> step.code().startsWith("System.out.println"))
        .extracting(this::array)
        .containsExactly(List.of(1, 2, 3));
  }

  private static String helperBubbleProgram() {
    return """
        public class SortSample {
          public static void main(String[] args) {
            int[] nums = {3, 1, 2};
            for (int pass = 0; pass < nums.length - 1; pass++) {
              for (int scan = 0; scan < nums.length - 1 - pass; scan++) {
                if (nums[scan] > nums[scan + 1]) exchange(nums, scan, scan + 1);
              }
            }
            System.out.println(java.util.Arrays.toString(nums));
          }
          private static void exchange(int[] values, int left, int right) {
            int temporary = values[left]; values[left] = values[right]; values[right] = temporary;
          }
        }
        """;
  }

  private static String adjacentInsertionProgram() {
    return """
        public class SortSample {
          public static void main(String[] args) {
            int[] nums = {3, 1, 2};
            for (int pass = 1; pass < nums.length; pass++) {
              for (int scan = pass; scan > 0 && nums[scan] < nums[scan - 1]; scan--) {
                int temporary = nums[scan];
                nums[scan] = nums[scan - 1];
                nums[scan - 1] = temporary;
              }
            }
            System.out.println(java.util.Arrays.toString(nums));
          }
        }
        """;
  }
}
