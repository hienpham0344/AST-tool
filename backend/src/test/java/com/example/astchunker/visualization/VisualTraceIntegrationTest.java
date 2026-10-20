package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.BreakpointMapper;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.mapping.IntervalValueProjection;
import com.example.astchunker.mapping.IntegerListProjection;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.VisualEvent;
import com.example.astchunker.model.VisualState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualTraceIntegrationTest {
  @Test
  void variableIntegerListWindowHasProjectedRuntimeFrames() {
    String source = """
        public class ListWindowSample {
          static void run(java.util.List<Integer> values, int limit) {
            int left = 0, total = 0;
            for (int scan = 0; scan < values.size(); scan++) {
              total += values.get(scan);
              while (total >= limit && left <= scan) {
                total -= values.get(left);
                left++;
              }
            }
          }
          public static void main(String[] args) {
            run(new java.util.ArrayList<>(java.util.Arrays.asList(1, 2, 3)), 3);
          }
        }
        """;
    var analysis = new AstAnalyzer().analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints).singleElement().satisfies(hint ->
        assertThat(hint.slidingWindow().kind()).isEqualTo("VARIABLE_SIZE"));
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var run = new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
          .observe(target, analysis, new IntervalValueProjection(java.util.Map.of()),
              IntegerListProjection.fromHints(hints));
      var states = new VisualTraceBuilder().build(analysis, hints, run.executions()).stream()
          .flatMap(step -> step.visualStates().stream())
          .filter(state -> state.slidingWindowFrame() != null).toList();
      assertThat(states).isNotEmpty();
      assertThat(states).anySatisfy(state -> {
        assertThat(state.array().status()).isEqualTo("AVAILABLE");
        assertThat(state.array().values()).containsExactly(1, 2, 3);
        assertThat(state.slidingWindowFrame().inputObjectId()).isNotNull();
      });
    }
  }

  @Test
  void sameDirectionIntegerListHasScopedPointerFrames() {
    String source = """
        import java.util.*;
        public class IntegerListPointerSample {
          static int compact(List<Integer> data) {
            int slow = 0;
            for (int scan = 1; scan < data.size(); scan++) {
              if (!data.get(scan).equals(data.get(slow))) {
                slow++;
                data.set(slow, data.get(scan));
              }
            }
            return slow + 1;
          }
          public static void main(String[] args) {
            compact(new ArrayList<>(Arrays.asList(1, 1, 2, 2, 3)));
          }
        }
        """;
    var analysis = new AstAnalyzer().analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("two-pointers");
      assertThat(hint.twoPointers().movement().name()).isEqualTo("SAME_DIRECTION");
    });
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var run = new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
          .observe(target, analysis, new IntervalValueProjection(java.util.Map.of()),
              IntegerListProjection.fromHints(hints));
      var frames = new VisualTraceBuilder().build(analysis, hints, run.executions()).stream()
          .flatMap(step -> step.visualStates().stream())
          .map(VisualState::twoPointersFrame)
          .filter(java.util.Objects::nonNull)
          .toList();
      assertThat(frames).isNotEmpty();
      assertThat(frames).anySatisfy(frame -> {
        assertThat(frame.movement()).isEqualTo("SAME_DIRECTION");
        assertThat(frame.inputObjectId()).isNotNull();
        assertThat(frame.length()).isEqualTo(5);
      });
    }
  }

  @Test
  void listActivitySelectionVerifiesSourceOrderAndObservedResult() throws Exception {
    String source = Files.readString(Path.of("examples/greedy/ActivitySelectionListSample.java"));
    var analysis = new AstAnalyzer().analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("activity-selection");
      assertThat(hint.activitySelection().orderingEvidence().name()).isEqualTo("SOURCE_PROVEN");
    });
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var run = new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
          .observe(target, analysis, IntervalValueProjection.fromHints(hints));
      var frames = new VisualTraceBuilder().build(analysis, hints, run.executions()).stream()
          .flatMap(step -> step.visualStates().stream())
          .map(VisualState::activitySelectionFrame)
          .filter(java.util.Objects::nonNull)
          .toList();
      assertThat(frames).anySatisfy(frame ->
          {
            assertThat(frame.optimalityStatus()).isEqualTo("VERIFIED_FOR_RUN");
            assertThat(frame.selectedIndices()).hasSize(bruteForceMaximum(new int[][] {
                {5, 7}, {1, 3}, {3, 5}, {2, 4}
            }));
          });
    }
  }

  private static int bruteForceMaximum(int[][] intervals) {
    int best = 0;
    for (int subset = 0; subset < (1 << intervals.length); subset++) {
      boolean compatible = true;
      for (int left = 0; left < intervals.length; left++) {
        for (int right = left + 1; right < intervals.length; right++) {
          if ((subset & (1 << left)) != 0 && (subset & (1 << right)) != 0
              && intervals[left][0] < intervals[right][1]
              && intervals[right][0] < intervals[left][1]) compatible = false;
        }
      }
      if (compatible) best = Math.max(best, Integer.bitCount(subset));
    }
    return best;
  }

  @Test
  void halfOpenZeroLengthDoesNotReceiveAnOptimalityClaim() {
    String source = """
        import java.util.Arrays;
        public class ZeroLengthActivitySample {
          static int select(int[][] data) {
            Arrays.sort(data, (a, b) -> Integer.compare(a[1], b[1]));
            int last = Integer.MIN_VALUE, count = 0;
            for (int i = 0; i < data.length; i++) {
              int[] slot = data[i];
              if (slot[0] >= last) { count++; last = slot[1]; }
            }
            return count;
          }
          public static void main(String[] args) {
            int[][] data = {{0, 3}, {1, 1}};
            select(data);
          }
        }
        """;
    var analysis = new AstAnalyzer().analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints).singleElement().satisfies(hint ->
        assertThat(hint.activitySelection().orderingEvidence().name())
            .isEqualTo("SOURCE_PROVEN"));
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var run = new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
          .observe(target, analysis, IntervalValueProjection.fromHints(hints));
      var frames = new VisualTraceBuilder().build(analysis, hints, run.executions()).stream()
          .flatMap(step -> step.visualStates().stream())
          .map(VisualState::activitySelectionFrame)
          .filter(java.util.Objects::nonNull).toList();
      assertThat(frames).isNotEmpty();
      assertThat(frames).noneMatch(frame -> frame.optimalityStatus().equals("VERIFIED_FOR_RUN"));
    }
  }

  @Test
  void activitySelectionConfirmsAWriteOnlyAfterLaterJdiSnapshot() {
    String source = """
        import java.util.ArrayList;
        import java.util.List;
        public class ActivityRuntimeSample {
          record Slot(int start, int end) {}
          static int select(List<Slot> values) {
            int last = Integer.MIN_VALUE, count = 0;
            for (Slot current : values) {
              if (current.start() >= last) {
                count++;
                last = current.end();
              }
            }
            return count;
          }
          public static void main(String[] args) {
            List<Slot> values = new ArrayList<>();
            values.add(new Slot(1, 3));
            values.add(new Slot(2, 4));
            values.add(new Slot(3, 5));
            select(values);
          }
        }
        """;
    var analysis = new AstAnalyzer().analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints).singleElement().satisfies(h ->
        assertThat(h.type()).isEqualTo("activity-selection"));
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var run = new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
          .observe(target, analysis, IntervalValueProjection.fromHints(hints));
      var frames = new VisualTraceBuilder().build(analysis, hints, run.executions()).stream()
          .flatMap(step -> step.visualStates().stream())
          .map(VisualState::activitySelectionFrame)
          .filter(java.util.Objects::nonNull)
          .toList();
      assertThat(frames).isNotEmpty();
      assertThat(frames).anySatisfy(frame -> {
        assertThat(frame.decision()).isEqualTo("ACCEPT");
        assertThat(frame.effectStatus()).isEqualTo("PENDING");
      });
      assertThat(frames).anySatisfy(frame ->
          assertThat(frame.effectStatus()).isEqualTo("OBSERVED_AFTER_WRITE"));
    }
  }

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
          while (total >= target && begin <= end) {
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
    var frames =
        steps.stream()
            .flatMap(s -> s.visualStates().stream())
            .map(VisualState::sortFrame)
            .filter(java.util.Objects::nonNull)
            .toList();

    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.phase()).isIn("COMPARISON", "SWAP_COMPLETED_AND_COMPARISON");
              assertThat(frame.comparison().firstIndex()).isEqualTo(0);
              assertThat(frame.comparison().secondIndex()).isEqualTo(1);
              assertThat(frame.comparison().firstValue()).isEqualTo(2);
              assertThat(frame.comparison().secondValue()).isEqualTo(1);
              assertThat(frame.comparison().swapRequired()).isTrue();
            });
    var partial =
        steps.stream()
            .filter(s -> s.code().startsWith("nums[scan + 1] = temp"))
            .findFirst()
            .orElseThrow()
            .visualStates()
            .get(0);
    assertThat(partial.array().values()).containsExactly(1, 1);
    assertThat(partial.sortFrame().phase()).isEqualTo("SWAP_RIGHT_WRITE_PENDING");
    assertThat(partial.sortFrame().pendingMutation().status()).isEqualTo("PENDING_BEFORE_LOCATION");
    assertThat(partial.sortFrame().pendingMutation().value()).isEqualTo(2);

    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.completedMutation()).isNotNull();
              assertThat(frame.completedMutation().status()).isEqualTo("OBSERVED_AFTER_WRITE");
              assertThat(frame.completedMutation().fromIndex()).isEqualTo(0);
              assertThat(frame.completedMutation().toIndex()).isEqualTo(1);
            });
    var terminal =
        steps.stream()
            .filter(s -> s.code().startsWith("System.out.println"))
            .findFirst()
            .orElseThrow()
            .visualStates()
            .get(0);
    assertThat(terminal.array().values()).containsExactly(1, 2);
    assertThat(terminal.sortFrame().phase()).isEqualTo("SORT_FINISHED");
    assertThat(terminal.sortFrame().sortedRegion().status()).isEqualTo("VERIFIED_SORTED");
  }

  @Test
  void selectionSortTraceShowsCandidateUpdatesSwapWritesAndSortedPrefix() {
    var steps =
        run(
            """
        int[] nums = {3, 1, 2};
        for (int pass = 0; pass < nums.length - 1; pass++) {
          int min = pass;
          for (int scan = pass + 1; scan < nums.length; scan++) {
            if (nums[scan] < nums[min]) {
              min = scan;
            }
          }
          int temp = nums[pass];
          nums[pass] = nums[min];
          nums[min] = temp;
        }
        System.out.println(java.util.Arrays.toString(nums));
        """);
    var frames =
        steps.stream()
            .flatMap(step -> step.visualStates().stream())
            .map(VisualState::sortFrame)
            .filter(java.util.Objects::nonNull)
            .toList();

    assertThat(frames.stream().filter(frame -> frame.comparison() != null).toList())
        .anySatisfy(
            frame -> {
              assertThat(frame.phase()).isEqualTo("SELECTION_COMPARE");
              assertThat(frame.comparison().firstIndex()).isEqualTo(1);
              assertThat(frame.comparison().secondIndex()).isEqualTo(0);
              assertThat(frame.comparison().firstValue()).isEqualTo(1);
              assertThat(frame.comparison().secondValue()).isEqualTo(3);
              assertThat(frame.comparison().action()).isEqualTo("UPDATE_SELECTED_INDEX");
              assertThat(frame.comparison().actionRequired()).isTrue();
              assertThat(frame.comparison().swapRequired()).isNull();
            });

    var update =
        steps.stream().filter(step -> step.code().equals("min = scan;")).findFirst().orElseThrow();
    assertThat(update.visualStates().get(0).sortFrame().phase())
        .isEqualTo("SELECTION_UPDATE_PENDING");
    assertThat(update.visualStates().get(0).sortFrame().pendingMutation())
        .satisfies(
            mutation -> {
              assertThat(mutation.fromIndex()).isEqualTo(0);
              assertThat(mutation.toIndex()).isEqualTo(1);
              assertThat(mutation.value()).isEqualTo(1);
              assertThat(mutation.status()).isEqualTo("PENDING_BEFORE_LOCATION");
            });

    var partial =
        steps.stream()
            .filter(step -> step.code().equals("nums[min] = temp;"))
            .findFirst()
            .orElseThrow()
            .visualStates()
            .get(0);
    assertThat(partial.array().values()).containsExactly(1, 1, 2);
    assertThat(partial.sortFrame().pendingMutation().value()).isEqualTo(3);
    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.completedMutation()).isNotNull();
              assertThat(frame.completedMutation().fromIndex()).isEqualTo(0);
              assertThat(frame.completedMutation().toIndex()).isEqualTo(1);
              assertThat(frame.completedMutation().status()).isEqualTo("OBSERVED_AFTER_WRITE");
            });

    var terminal =
        steps.stream()
            .filter(step -> step.code().startsWith("System.out.println"))
            .findFirst()
            .orElseThrow()
            .visualStates()
            .get(0);
    assertThat(terminal.array().values()).containsExactly(1, 2, 3);
    assertThat(terminal.sortFrame().phase()).isEqualTo("SORT_FINISHED");
    assertThat(terminal.sortFrame().sortedRegion().status()).isEqualTo("VERIFIED_SORTED");
  }

  @Test
  void insertionSortTraceShowsComparisonsShiftsAndKeyPlacement() {
    var steps =
        run(
            """
        int[] nums = {3, 1, 2};
        for (int pass = 1; pass < nums.length; pass++) {
          int key = nums[pass];
          int scan = pass - 1;
          while (scan >= 0 && nums[scan] > key) {
            nums[scan + 1] = nums[scan];
            scan--;
          }
          nums[scan + 1] = key;
        }
        System.out.println(java.util.Arrays.toString(nums));
        """);
    var frames =
        steps.stream()
            .flatMap(step -> step.visualStates().stream())
            .map(VisualState::sortFrame)
            .filter(java.util.Objects::nonNull)
            .toList();

    assertThat(frames.stream().filter(frame -> frame.comparison() != null).toList())
        .anySatisfy(
            frame -> {
              assertThat(frame.phase()).isEqualTo("INSERTION_COMPARE");
              assertThat(frame.comparison().firstIndex()).isEqualTo(0);
              assertThat(frame.comparison().firstValue()).isEqualTo(3);
              assertThat(frame.comparison().secondIndex()).isNull();
              assertThat(frame.comparison().secondValue()).isEqualTo(1);
              assertThat(frame.comparison().action()).isEqualTo("SHIFT_RIGHT");
              assertThat(frame.comparison().actionRequired()).isTrue();
            });

    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.phase()).isEqualTo("SHIFT_RIGHT_PENDING");
              assertThat(frame.pendingMutation().fromIndex()).isEqualTo(0);
              assertThat(frame.pendingMutation().toIndex()).isEqualTo(1);
              assertThat(frame.pendingMutation().value()).isEqualTo(3);
              assertThat(frame.pendingMutation().status()).isEqualTo("PENDING_BEFORE_LOCATION");
            });
    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.completedMutation()).isNotNull();
              assertThat(frame.completedMutation().kind()).isEqualTo("SHIFT_RIGHT_COMPLETED");
              assertThat(frame.completedMutation().status()).isEqualTo("OBSERVED_AFTER_WRITE");
            });
    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.phase()).isEqualTo("INSERT_KEY_PENDING");
              assertThat(frame.pendingMutation().kind()).isEqualTo("INSERT_KEY");
              assertThat(frame.pendingMutation().toIndex()).isZero();
              assertThat(frame.pendingMutation().value()).isEqualTo(1);
            });
    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.completedMutation()).isNotNull();
              assertThat(frame.completedMutation().kind()).isEqualTo("INSERT_KEY_COMPLETED");
              assertThat(frame.completedMutation().toIndex()).isZero();
              assertThat(frame.phase()).isEqualTo("INSERT_KEY_COMPLETED");
            });
    assertThat(frames.stream().filter(frame -> frame.comparison() != null).toList())
        .anySatisfy(
            frame -> {
              assertThat(frame.comparison().status()).isEqualTo("LEFT_BOUNDARY_REACHED");
              assertThat(frame.comparison().action()).isEqualTo("STOP_SHIFTING");
            });
    var terminal =
        steps.stream()
            .filter(step -> step.code().startsWith("System.out.println"))
            .findFirst()
            .orElseThrow()
            .visualStates()
            .get(0);
    assertThat(terminal.array().values()).containsExactly(1, 2, 3);
    assertThat(terminal.sortFrame().phase()).isEqualTo("SORT_FINISHED");
    assertThat(terminal.sortFrame().sortedRegion().status()).isEqualTo("VERIFIED_SORTED");
  }

  @Test
  void insertionSortTraceVerifiesDescendingOrder() {
    var steps =
        run(
            """
        int[] nums = {1, 3, 2};
        for (int pass = 1; pass < nums.length; pass++) {
          int key = nums[pass];
          int scan = pass - 1;
          while (scan >= 0 && nums[scan] < key) {
            nums[scan + 1] = nums[scan];
            scan--;
          }
          nums[scan + 1] = key;
        }
        System.out.println(java.util.Arrays.toString(nums));
        """);
    var terminal =
        steps.stream()
            .filter(step -> step.code().startsWith("System.out.println"))
            .findFirst()
            .orElseThrow()
            .visualStates()
            .get(0);

    assertThat(terminal.algorithm()).isEqualTo("insertion-sort");
    assertThat(terminal.array().values()).containsExactly(3, 2, 1);
    assertThat(terminal.sortFrame().sortedRegion().status()).isEqualTo("VERIFIED_SORTED");
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
