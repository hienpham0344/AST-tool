package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.BreakpointMapper;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.PrefixSumFrame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrefixSumRuntimeIntegrationTest {
  private List<ExecutionObservation> trace(String source) {
    var analysis = new AstAnalyzer().analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints).anyMatch(h -> h.prefixSum() != null);
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var result =
          new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
              .observe(target, analysis);
      assertThat(result.warnings()).noneMatch(w -> w.contains("timed out"));
      return new VisualTraceBuilder().build(analysis, hints, result.executions());
    }
  }

  private List<PrefixSumFrame> frames(String source) {
    return trace(source).stream()
        .flatMap(s -> s.visualStates().stream())
        .map(s -> s.prefixSumFrame())
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  @Test
  void zeroSentinelWithNegativeAndZeroValuesConfirmsOnlyEarlierCells() throws Exception {
    String source = Files.readString(Path.of("examples/prefixsum/PrefixSumSentinelSample.java"));
    var frames = frames(source);
    assertThat(frames)
        .anySatisfy(
            f -> {
              assertThat(f.recurrenceStatus()).isEqualTo("PENDING");
              assertThat(f.sourceIndex()).isZero();
              assertThat(f.confirmedPrefixEndExclusive()).isEqualTo(1);
              assertThat(f.confirmedCells()).hasSize(1);
              assertThat(f.result().values()).containsExactly(0, 0, 0, 0, 0);
            });
    assertThat(frames)
        .anySatisfy(
            f -> {
              assertThat(f.recurrenceStatus()).isEqualTo("PENDING");
              assertThat(f.sourceIndex()).isEqualTo(3);
              assertThat(f.sourceValue()).isEqualTo(0);
              assertThat(f.previousPrefixValue()).isEqualTo(4);
              assertThat(f.confirmedPrefixEndExclusive()).isEqualTo(4);
              assertThat(f.confirmedCells()).hasSize(4);
            });
    assertThat(frames)
        .anySatisfy(
            f -> {
              assertThat(f.query()).isNotNull();
              assertThat(f.query().status()).isEqualTo("PENDING");
              assertThat(f.query().upperValue()).isEqualTo(4);
              assertThat(f.query().lowerValue()).isEqualTo(2);
              assertThat(f.query().result()).isNull();
            });
    assertThat(frames)
        .anySatisfy(
            f -> {
              assertThat(f.query()).isNotNull();
              assertThat(f.query().status()).isEqualTo("OBSERVED");
              assertThat(f.query().result()).isEqualTo(2);
            });
  }

  @Test
  void sameLengthWhileKeepsLongPrecisionAndSeedPending() throws Exception {
    String source =
        Files.readString(Path.of("examples/prefixsum/PrefixSumSameLengthSample.java"))
            .replace("{2L, -1L, 3L, 0L}", "{9007199254740993L, -1L, 0L, 3L}");
    var frames = frames(source);
    assertThat(frames)
        .anySatisfy(
            f -> {
              assertThat(f.seedStatus()).isEqualTo("PENDING");
              assertThat(f.confirmedCells()).isEmpty();
              assertThat(f.result().values()).containsExactly(0, 0, 0, 0);
            });
    assertThat(frames)
        .anySatisfy(
            f -> {
              assertThat(f.recurrenceStatus()).isEqualTo("PENDING");
              assertThat(f.currentIndex()).isEqualTo(1);
              assertThat(f.previousPrefixValue()).isEqualTo(9007199254740993L);
              assertThat(f.confirmedPrefixEndExclusive()).isEqualTo(1);
            });
    assertThat(frames)
        .anySatisfy(
            f -> {
              assertThat(f.confirmedPrefixEndExclusive()).isEqualTo(4);
              assertThat(f.result().values())
                  .containsExactly(
                      9007199254740993L, 9007199254740992L, 9007199254740992L, 9007199254740995L);
            });
  }

  @Test
  void intOverflowAndConfirmedZeroUseObservedJavaValues() {
    var frames =
        frames(
            """
            public class OverflowSample {
              public static void main(String[] args) {
                int[] data = {2147483647, 1, -2147483648, 0};
                int[] sums = new int[data.length + 1];
                for (int i=0; i<data.length; i++) {
                  sums[i+1] = sums[i] + data[i];
                }
                int done = sums[4];
              }
            }
            """);
    assertThat(frames)
        .anySatisfy(
            f -> {
              assertThat(f.recurrenceStatus()).isEqualTo("PENDING");
              assertThat(f.currentIndex()).isEqualTo(2);
              assertThat(f.previousPrefixValue()).isEqualTo(Integer.MIN_VALUE);
            });
    assertThat(frames)
        .anySatisfy(
            f -> {
              assertThat(f.recurrenceStatus()).isEqualTo("PENDING");
              assertThat(f.currentIndex()).isEqualTo(3);
              assertThat(f.confirmedCells()).hasSize(4);
              assertThat(f.confirmedCells().get(3).value()).isEqualTo(0);
            });
    assertThat(frames)
        .anySatisfy(
            f -> {
              assertThat(f.confirmedPrefixEndExclusive()).isEqualTo(5);
              assertThat(f.result().values())
                  .containsExactly(0, Integer.MAX_VALUE, Integer.MIN_VALUE, 0, 0);
            });
  }

  @Test
  void recursiveInvocationsNeverShareProgressOrReferences() {
    var steps =
        trace(
            """
            public class RecursiveSample {
              static int[] build(int depth) {
                int[] data = {0, -2, 2};
                int[] sums = new int[data.length + 1];
                if (depth > 0) {
                  int[] ignored = build(depth - 1);
                }
                for (int i=0; i<data.length; i++) {
                  sums[i+1] = sums[i] + data[i];
                }
                return sums;
              }
              public static void main(String[] args) {
                int[] data = {0, -2, 2};
                int[] first = build(1);
                int[] second = build(0);
              }
            }
            """);
    var recurrenceSteps =
        steps.stream()
            .filter(
                s ->
                    s.visualStates().stream()
                        .anyMatch(
                            v ->
                                v.prefixSumFrame() != null
                                    && v.prefixSumFrame().recurrenceStatus().equals("PENDING")))
            .toList();
    assertThat(recurrenceSteps).hasSize(9);
    assertThat(recurrenceSteps.stream().map(s -> s.context().invocationId()).distinct()).hasSize(3);
    assertThat(
            recurrenceSteps.stream()
                .map(s -> s.visualStates().get(0).prefixSumFrame().resultArrayObjectId())
                .distinct())
        .hasSize(3);
    assertThat(recurrenceSteps)
        .allSatisfy(
            s -> {
              var f = s.visualStates().get(0).prefixSumFrame();
              assertThat(f.invocationId()).isEqualTo(s.context().invocationId());
              assertThat(f.confirmedPrefixEndExclusive()).isEqualTo(f.currentIndex() + 1);
              assertThat(f.confirmedCells()).hasSize(f.currentIndex().intValue() + 1);
              assertThat(f.sourceArrayObjectId()).isNotEqualTo(f.resultArrayObjectId());
            });
  }
}
