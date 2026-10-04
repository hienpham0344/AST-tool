package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.BreakpointMapper;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.ExecutionObservation;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Runtime baseline: these tests do not claim that sort pattern detection exists yet. */
class SortRuntimeIntegrationTest {
  private static final String BUBBLE = """
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
  private static final String SELECTION = """
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
  private static final String INSERTION = """
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

  record Case(String name, String algorithm, String input, List<Integer> expected) {
    @Override
    public String toString() { return name + " [" + input + "]"; }
  }

  static Stream<Case> cases() {
    return Stream.of(BUBBLE, SELECTION, INSERTION).flatMap(algorithm ->
        Stream.of("", "7", "1,2,3", "3,2,1", "2,1,2,1", "0,-3,2,-1")
            .map(input -> new Case(
                algorithm.equals(BUBBLE) ? "bubble" : algorithm.equals(SELECTION) ? "selection" : "insertion",
                algorithm, input,
                input.isEmpty() ? List.of() : Arrays.stream(input.split(","))
                    .map(Integer::valueOf).sorted().toList())));
  }

  private List<ExecutionObservation> run(String input, String algorithm) {
    String source = "public class SortSample {\npublic static void main(String[] args) {\n"
        + "int[] nums = {" + input + "};\n" + algorithm
        + "System.out.println(java.util.Arrays.toString(nums));\n}\n}";
    var analysis = new AstAnalyzer().analyze(source);
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var trace = new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
          .observe(target, analysis);
      assertThat(trace.warnings()).noneMatch(w -> w.contains("timed out"));
      assertThat(trace.executions()).isNotEmpty();
      return trace.executions();
    }
  }

  private Object array(ExecutionObservation step) {
    return step.variables().stream().filter(v -> v.variableName().equals("nums"))
        .findFirst().orElseThrow().visualValue();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void capturesFinalArrayAtExplicitObservationPoint(Case sample) {
    var steps = run(sample.input(), sample.algorithm());
    var end = steps.stream().filter(s -> s.code().startsWith("System.out.println"))
        .findFirst().orElseThrow();
    assertThat(array(end)).isEqualTo(sample.expected());
    assertThat(end.snapshotPhase()).isEqualTo("BEFORE_LOCATION");
  }

  @Test
  void bubbleSwapHasIntermediateDuplicateValueNotAnAtomicSwap() {
    var steps = run("2,1", BUBBLE);
    var secondWrite = steps.stream().filter(s -> s.code().equals("nums[j + 1] = temp;"))
        .findFirst().orElseThrow();
    assertThat(array(secondWrite)).isEqualTo(List.of(1, 1));
    assertThat(secondWrite.variables()).anySatisfy(v -> {
      assertThat(v.variableName()).isEqualTo("temp");
      assertThat(v.visualValue()).isEqualTo(2);
    });
  }

  @Test
  void supportsDescendingBubbleRuntime() {
    var steps = run("1,3,2", BUBBLE.replace("nums[j] >", "nums[j] <"));
    var end = steps.stream().filter(s -> s.code().startsWith("System.out.println"))
        .findFirst().orElseThrow();
    assertThat(array(end)).isEqualTo(List.of(3, 2, 1));
  }
}
