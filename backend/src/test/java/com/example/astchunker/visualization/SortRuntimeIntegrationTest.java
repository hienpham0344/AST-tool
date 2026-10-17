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
  void whileBubbleKeepsObservedSwapAndVerifiedEnding() {
    String whileBubble =
        """
        int pass = 0;
        while (pass < nums.length - 1) {
          int scan = 0;
          while (scan < nums.length - 1 - pass) {
            if (nums[scan] > nums[scan + 1]) {
              int saved = nums[scan];
              nums[scan] = nums[scan + 1];
              nums[scan + 1] = saved;
            }
            scan++;
          }
          pass++;
        }
        """;
    var steps = run("2,1", whileBubble);
    assertThat(steps.stream().filter(s -> s.code().equals("nums[scan + 1] = saved;")).toList())
        .anySatisfy(
            step ->
                assertThat(step.visualStates().get(0).sortFrame().pendingMutation().status())
                    .isEqualTo("PENDING_BEFORE_LOCATION"));
    var ending = steps.stream().filter(s -> s.code().startsWith("System.out.println")).findFirst().orElseThrow();
    assertThat(array(ending)).isEqualTo(List.of(1, 2));
    assertThat(ending.visualStates().get(0).sortFrame().sortedRegion().status())
        .isEqualTo("VERIFIED_SORTED");
  }

  @Test
  void bubbleHelperSwapUsesObservedWritesAcrossMethods() {
    String source =
        """
        public class SortSample {
          public static void main(String[] args) {
            int[] nums = {2, 1};
            sort(nums);
          }
          static void sort(int[] nums) {
            for (int pass = 0; pass < nums.length - 1; pass++) {
              for (int scan = 0; scan < nums.length - 1 - pass; scan++) {
                if (nums[scan] > nums[scan + 1]) swap(nums, scan, scan + 1);
              }
            }
            System.out.println(java.util.Arrays.toString(nums));
          }
          static void swap(int[] values, int left, int right) {
            int saved = values[left];
            values[left] = values[right];
            values[right] = saved;
          }
        }
        """;
    var steps = runSource(source);
    var helperWrite =
        steps.stream().filter(s -> s.code().equals("values[right] = saved;")).findFirst().orElseThrow();
    assertThat(helperWrite.visualStates()).hasSize(1);
    assertThat(helperWrite.visualStates().get(0).array().values()).containsExactly(1, 1);
    assertThat(helperWrite.visualStates().get(0).sortFrame().pendingMutation().status())
        .isEqualTo("PENDING_BEFORE_LOCATION");
    var ending = steps.stream().filter(s -> s.code().startsWith("System.out.println")).findFirst().orElseThrow();
    assertThat(array(ending)).isEqualTo(List.of(1, 2));
    assertThat(ending.visualStates().get(0).sortFrame().sortedRegion().status())
        .isEqualTo("VERIFIED_SORTED");
    assertThat(steps.stream().flatMap(step -> step.visualStates().stream()).map(state -> state.sortFrame()))
        .anySatisfy(frame -> assertThat(frame.completedMutation()).isNotNull());
  }

  @Test
  void earlyExitBubbleVerifiesAllSixRuntimeInputs() {
    String algorithm =
        """
        for (int pass = 0; pass < nums.length - 1; pass++) {
          boolean changed = false;
          for (int scan = 0; scan < nums.length - 1 - pass; scan++) {
            if (nums[scan] > nums[scan + 1]) {
              int saved = nums[scan];
              nums[scan] = nums[scan + 1];
              nums[scan + 1] = saved;
              changed = true;
            }
          }
          if (!changed) break;
        }
        """;
    var endings = runAllCases(algorithm).stream()
        .filter(s -> s.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(EXPECTED.size());
    assertThat(endings).extracting(this::array).containsExactlyElementsOf(EXPECTED);
    assertThat(endings)
        .allSatisfy(end -> assertThat(end.visualStates().get(0).sortFrame().sortedRegion().status())
            .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void whileSelectionVerifiesAllSixRuntimeInputs() {
    String algorithm =
        """
        int pass = 0;
        while (pass < nums.length - 1) {
          int selected = pass;
          int scan = pass + 1;
          while (scan < nums.length) {
            if (nums[scan] < nums[selected]) selected = scan;
            scan++;
          }
          int saved = nums[pass];
          nums[pass] = nums[selected];
          nums[selected] = saved;
          pass++;
        }
        """;
    var endings = runAllCases(algorithm).stream()
        .filter(s -> s.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(EXPECTED.size());
    assertThat(endings).extracting(this::array).containsExactlyElementsOf(EXPECTED);
    assertThat(endings)
        .allSatisfy(end -> assertThat(end.visualStates().get(0).sortFrame().sortedRegion().status())
            .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void whileInsertionVerifiesAllSixRuntimeInputs() {
    String algorithm =
        """
        int pass = 1;
        while (pass < nums.length) {
          int key = nums[pass];
          int scan = pass - 1;
          while (scan >= 0 && nums[scan] > key) {
            nums[scan + 1] = nums[scan];
            scan--;
          }
          nums[scan + 1] = key;
          pass++;
        }
        """;
    var endings = runAllCases(algorithm).stream()
        .filter(s -> s.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(EXPECTED.size());
    assertThat(endings).extracting(this::array).containsExactlyElementsOf(EXPECTED);
    assertThat(endings)
        .allSatisfy(end -> assertThat(end.visualStates().get(0).sortFrame().sortedRegion().status())
            .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void selectionHelperSwapConfirmsMutationAfterReturning() {
    String source =
        """
        public class SortSample {
          public static void main(String[] args) { sort(new int[] {2, 1}); }
          static void sort(int[] nums) {
            for (int pass = 0; pass < nums.length - 1; pass++) {
              int selected = pass;
              for (int scan = pass + 1; scan < nums.length; scan++) {
                if (nums[scan] < nums[selected]) selected = scan;
              }
              swap(nums, pass, selected);
            }
            System.out.println(java.util.Arrays.toString(nums));
          }
          static void swap(int[] values, int left, int right) {
            int saved = values[left];
            values[left] = values[right];
            values[right] = saved;
          }
        }
        """;
    var steps = runSource(source);
    var pending = steps.stream().filter(s -> s.code().equals("values[right] = saved;"))
        .findFirst().orElseThrow();
    assertThat(pending.visualStates()).hasSize(1);
    assertThat(pending.visualStates().get(0).sortFrame().pendingMutation().status())
        .isEqualTo("PENDING_BEFORE_LOCATION");
    var ending = steps.stream().filter(s -> s.code().startsWith("System.out.println"))
        .findFirst().orElseThrow();
    assertThat(array(ending)).isEqualTo(List.of(1, 2));
    assertThat(ending.visualStates().get(0).sortFrame().completedMutation().status())
        .isEqualTo("OBSERVED_AFTER_WRITE");
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
}
