package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.BreakpointMapper;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.VisualEvent;
import com.example.astchunker.model.VisualState;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualTraceIntegrationTest {
  private List<ExecutionObservation> run(String body) {
    String source =
        "public class Sample { public static void main(String[] args) {\n" + body + "\n} }";
    var analysis = new AstAnalyzer().analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints).hasSize(1);
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var trace =
          new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
              .observe(target, analysis);
      assertThat(trace.warnings()).noneMatch(w -> w.contains("timed out"));
      return new VisualTraceBuilder().build(analysis, hints, trace.executions());
    }
  }

  @Test
  void binarySearchPreservesMissingMidBeforeDeclarationAndObservedMidAfterwards() {
    var steps =
        run(
            """
        int[] nums = {1, 3, 5, 7};
        int lo = 0, hi = nums.length - 1, target = 5;
        while (lo <= hi) {
          int mid = lo + (hi - lo) / 2;
          if (nums[mid] == target) break;
          if (nums[mid] < target) lo = mid + 1;
          else hi = mid - 1;
        }
        """);
    var declaration =
        steps.stream().filter(s -> s.code().startsWith("int mid")).findFirst().orElseThrow();
    assertThat(declaration.visualStates()).hasSize(1);
    assertThat(declaration.visualStates().get(0).pointers().get("mid").status())
        .isEqualTo("MISSING");
    var comparisons = steps.stream().filter(s -> s.code().startsWith("if (nums[mid] ==")).toList();
    assertThat(comparisons)
        .extracting(s -> s.visualStates().get(0).pointers().get("mid").index())
        .containsExactly(1L, 2L);
    assertThat(comparisons)
        .allSatisfy(
            s -> {
              assertThat(s.visualStates().get(0).algorithm()).isEqualTo("binary-search");
              assertThat(s.visualStates().get(0).status()).isEqualTo("READY");
              assertThat(s.snapshotPhase()).isEqualTo("BEFORE_LOCATION");
            });
  }

  @Test
  void twoPointersReportsObservedArrayChangesWithoutClaimingAtomicSwaps() {
    var steps =
        run(
            """
        int[] nums = {1, 2, 3, 4};
        int a = 0, b = nums.length - 1;
        while (a < b) {
          int temp = nums[a];
          nums[a] = nums[b];
          nums[b] = temp;
          a++;
          b--;
        }
        System.out.println(nums[0]);
        """);
    var arrayChanges =
        steps.stream()
            .flatMap(s -> s.visualEvents().stream())
            .filter(e -> e.type().equals("ARRAY_CHANGED"))
            .toList();
    assertThat(arrayChanges).isNotEmpty();
    assertThat(arrayChanges)
        .anySatisfy(
            e -> {
              assertThat(((VisualState.ArrayValue) e.before()).values())
                  .containsExactly(1, 2, 3, 4);
              assertThat(((VisualState.ArrayValue) e.after()).values()).containsExactly(4, 2, 3, 4);
            });
    assertThat(steps.stream().flatMap(s -> s.visualEvents().stream()))
        .extracting(VisualEvent::type)
        .contains("POINTER_CHANGED", "STATE_RESET", "STATE_LEFT");
    assertThat(steps.get(steps.size() - 1).visualStates()).isEmpty();
  }

  @Test
  void fixedWindowExposesCandidateRangeSeparatelyFromPreAdditionAccumulator() {
    var steps =
        run(
            """
        int[] nums = {1, 2, 3, 4};
        int total = 0, k = 2;
        for (int end = 0; end < nums.length; end++) {
          total += nums[end];
          if (end >= k) total -= nums[end - k];
        }
        """);
    var additions = steps.stream().filter(s -> s.code().equals("total += nums[end];")).toList();
    assertThat(additions).hasSize(4);
    assertThat(additions)
        .extracting(s -> s.visualStates().get(0).scalars().get("accumulator").value())
        .containsExactly(0, 1, 3, 5);
    assertThat(additions)
        .extracting(s -> s.visualStates().get(0).range().start())
        .containsExactly(0L, 0L, 1L, 2L);
    assertThat(additions)
        .allSatisfy(
            s -> {
              var state = s.visualStates().get(0);
              assertThat(state.range().kind()).isEqualTo("FIXED_SIZE_CANDIDATE");
              assertThat(state.pointers()).doesNotContainKey("left");
              assertThat(state.notes()).contains("RANGE_DOES_NOT_PROVE_ACCUMULATOR_MEMBERSHIP");
            });
    assertThat(steps.stream().flatMap(s -> s.visualEvents().stream()))
        .extracting(VisualEvent::type)
        .contains("VALUE_CHANGED");
  }

  @Test
  void variableWindowUsesBothRuntimePointersAndDoesNotUpdateAccumulatorEarly() {
    var steps =
        run(
            """
        int[] nums = {1, 2, 3};
        int begin = 0, total = 0, target = 3;
        for (int end = 0; end < nums.length; end++) {
          total += nums[end];
          while (total >= target) {
            total -= nums[begin];
            begin++;
          }
        }
        """);
    var removals = steps.stream().filter(s -> s.code().equals("total -= nums[begin];")).toList();
    assertThat(removals).hasSize(3);
    assertThat(removals)
        .extracting(s -> s.visualStates().get(0).pointers().get("left").index())
        .containsExactly(0L, 1L, 2L);
    assertThat(removals)
        .extracting(s -> s.visualStates().get(0).scalars().get("accumulator").value())
        .containsExactly(3, 5, 3);
  }

  @Test
  void bubbleSortTraceSeparatesPendingWritesFromObservedCompletedSwap() {
    var steps =
        run(
            """
        int[] nums = {2, 1};
        for (int pass = 0; pass < nums.length - 1; pass++) {
          for (int scan = 0; scan < nums.length - 1 - pass; scan++) {
            if (nums[scan] > nums[scan + 1]) {
              int temp = nums[scan];
              nums[scan] = nums[scan + 1];
              nums[scan + 1] = temp;
            }
          }
        }
        System.out.println(java.util.Arrays.toString(nums));
        """);
    var frames = steps.stream().flatMap(s -> s.visualStates().stream())
        .map(VisualState::sortFrame).filter(java.util.Objects::nonNull).toList();

    assertThat(frames).anySatisfy(frame -> {
      assertThat(frame.phase()).isIn("COMPARISON", "SWAP_COMPLETED_AND_COMPARISON");
      assertThat(frame.comparison().firstIndex()).isEqualTo(0);
      assertThat(frame.comparison().secondIndex()).isEqualTo(1);
      assertThat(frame.comparison().firstValue()).isEqualTo(2);
      assertThat(frame.comparison().secondValue()).isEqualTo(1);
      assertThat(frame.comparison().swapRequired()).isTrue();
    });
    var partial = steps.stream().filter(s -> s.code().startsWith("nums[scan + 1] = temp"))
        .findFirst().orElseThrow().visualStates().get(0);
    assertThat(partial.array().values()).containsExactly(1, 1);
    assertThat(partial.sortFrame().phase()).isEqualTo("SWAP_RIGHT_WRITE_PENDING");
    assertThat(partial.sortFrame().pendingMutation().status()).isEqualTo("PENDING_BEFORE_LOCATION");
    assertThat(partial.sortFrame().pendingMutation().value()).isEqualTo(2);

    assertThat(frames).anySatisfy(frame -> {
      assertThat(frame.completedMutation()).isNotNull();
      assertThat(frame.completedMutation().status()).isEqualTo("OBSERVED_AFTER_WRITE");
      assertThat(frame.completedMutation().fromIndex()).isEqualTo(0);
      assertThat(frame.completedMutation().toIndex()).isEqualTo(1);
    });
    var terminal = steps.stream().filter(s -> s.code().startsWith("System.out.println"))
        .findFirst().orElseThrow().visualStates().get(0);
    assertThat(terminal.array().values()).containsExactly(1, 2);
    assertThat(terminal.sortFrame().phase()).isEqualTo("SORT_COMPLETED");
    assertThat(terminal.sortFrame().sortedRegion().status()).isEqualTo("COMPLETE_AFTER_LOOP");
  }

  @Test
  void capturesLargeArrayLengthAndHidesPointersOutsideCapturedPrefix() {
    var steps =
        run(
            """
        int[] nums = new int[120];
        int a = 0, b = nums.length - 1;
        while (a < b) {
          int x = nums[a] + nums[b];
          a++;
          b--;
          break;
        }
        """);
    var state = steps.stream().flatMap(s -> s.visualStates().stream()).findFirst().orElseThrow();
    assertThat(state.array().values()).hasSize(100);
    assertThat(state.array().length()).isEqualTo(120);
    assertThat(state.array().truncated()).isTrue();
    assertThat(state.pointers().get("right").status()).isEqualTo("NOT_CAPTURED");
  }

  @Test
  void halfOpenSearchDoesNotDrawTheExclusiveEndAsAnArrayCell() {
    var steps =
        run(
            """
        int[] nums = {1, 3, 5, 7};
        int lo = 0, hi = nums.length, target = 3;
        while (lo < hi) {
          int mid = lo + (hi - lo) / 2;
          if (nums[mid] < target) lo = mid + 1;
          else hi = mid;
        }
        """);
    var state = steps.stream().flatMap(s -> s.visualStates().stream()).findFirst().orElseThrow();
    assertThat(state.pointers().get("right").index()).isEqualTo(4);
    assertThat(state.pointers().get("right").status()).isEqualTo("OUT_OF_BOUNDS");
    assertThat(state.range().status()).isEqualTo("UNKNOWN");
    assertThat(state.notes()).contains("POINTER_SPAN_DOES_NOT_DECLARE_SEARCH_BOUND_CONVENTION");
  }
}
