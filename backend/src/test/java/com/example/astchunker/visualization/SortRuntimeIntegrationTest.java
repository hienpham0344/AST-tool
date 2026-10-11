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
