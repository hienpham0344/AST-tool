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
import java.util.Objects;
import org.junit.jupiter.api.Test;

class QuickSortRuntimeIntegrationTest {
  private static final String SOURCE = """
      public class QuickSample {
        public static void main(String[] args) {
          int[][] cases = {{}, {7}, {1, 2, 3}, {3, 2, 1}, {4, 2, 4, 1, 2}, {0, -3, 2, -1}};
          for (int[] data : cases) {
            quick(data, 0, data.length - 1);
            System.out.println(java.util.Arrays.toString(data));
          }
        }
        private static void quick(int[] data, int low, int high) {
          if (low >= high) return;
          int pivot = data[high];
          int store = low;
          for (int scan = low; scan < high; scan++) {
            if (data[scan] <= pivot) {
              int saved = data[store];
              data[store] = data[scan];
              data[scan] = saved;
              store++;
            }
          }
          int finalSaved = data[store];
          data[store] = data[high];
          data[high] = finalSaved;
          quick(data, low, store - 1);
          quick(data, store + 1, high);
        }
      }
      """;

  private List<ExecutionObservation> trace(String source) {
    AstAnalyzer parser = new AstAnalyzer();
    var analysis = parser.analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints).singleElement().satisfies(hint -> assertThat(hint.type()).isEqualTo("quick-sort"));
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var observed = new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
          .observe(target, analysis);
      assertThat(observed.warnings()).noneMatch(warning -> warning.contains("timed out"));
      return new VisualTraceBuilder().build(analysis, hints, observed.executions());
    }
  }

  @Test
  void tracksRecursiveFramesAndVerifiesEachCompletedArray() {
    List<ExecutionObservation> steps = trace(SOURCE);
    var quickSteps = steps.stream().filter(step -> step.context() != null
        && step.context().methodName().equals("quick")).toList();
    assertThat(quickSteps).isNotEmpty().allSatisfy(step -> {
      assertThat(step.visualStates()).hasSize(1);
      assertThat(step.visualStates().get(0).quickFrame().invocationId())
          .isEqualTo(step.context().invocationId());
    });
    var frames = quickSteps.stream().map(step -> step.visualStates().get(0).quickFrame()).toList();
    assertThat(frames).filteredOn(frame -> frame.comparison() != null)
        .anySatisfy(frame -> assertThat(frame.comparison().status()).isEqualTo("AVAILABLE_FROM_SNAPSHOT"));
    assertThat(frames).filteredOn(frame -> frame.pendingMutation() != null).isNotEmpty();
    assertThat(frames).filteredOn(frame -> frame.completedMutation() != null)
        .anySatisfy(frame -> assertThat(frame.completedMutation().status())
            .isEqualTo("OBSERVED_AFTER_WRITE"));
    assertThat(frames).anySatisfy(frame ->
        assertThat(frame.partitionStatus()).isEqualTo("VERIFIED_PARTITION"));
    var endings = steps.stream().filter(step -> step.code().startsWith("System.out.println"))
        .toList();
    assertThat(endings).hasSize(6);
    assertThat(endings).extracting(step -> step.visualStates().get(0).array().values())
        .containsExactly(List.of(), List.of(7), List.of(1, 2, 3), List.of(1, 2, 3),
            List.of(1, 2, 2, 4, 4), List.of(-3, -1, 0, 2));
    assertThat(endings).allSatisfy(step -> {
      assertThat(step.visualStates()).hasSize(1);
      assertThat(step.visualStates().get(0).quickFrame().fullArrayStatus())
          .isEqualTo("VERIFIED_SORTED");
    });
    assertThat(frames.stream().map(frame -> frame.invocationId()).filter(Objects::nonNull))
        .hasSizeGreaterThan(6).doesNotContainNull();
  }

  @Test
  void verifiesDescendingQuickOnRepeatedArrays() {
    var steps = trace(SOURCE.replace("data[scan] <= pivot", "data[scan] >= pivot"));
    var endings = steps.stream().filter(step -> step.code().startsWith("System.out.println"))
        .toList();
    assertThat(endings).hasSize(6);
    assertThat(endings).extracting(step -> step.visualStates().get(0).array().values())
        .containsExactly(List.of(), List.of(7), List.of(3, 2, 1), List.of(3, 2, 1),
            List.of(4, 4, 2, 2, 1), List.of(2, 0, -1, -3));
    assertThat(endings).allSatisfy(step ->
        assertThat(step.visualStates().get(0).quickFrame().fullArrayStatus())
            .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void whileScanUsesTheSameInvocationScopedTrace() {
    String whileSource = SOURCE
        .replace("for (int scan = low; scan < high; scan++) {",
            "int scan = low;\n    while (scan < high) {")
        .replace("        store++;\n      }\n    }\n    int finalSaved",
            "        store++;\n      }\n      scan++;\n    }\n    int finalSaved");
    assertThat(whileSource).contains("scan++;\n    }\n    int finalSaved");
    var steps = trace(whileSource);
    var endings = steps.stream().filter(step -> step.code().startsWith("System.out.println"))
        .toList();
    assertThat(endings).extracting(step -> step.visualStates().get(0).array().values())
        .containsExactly(List.of(), List.of(7), List.of(1, 2, 3), List.of(1, 2, 3),
            List.of(1, 2, 2, 4, 4), List.of(-3, -1, 0, 2));
  }
}
