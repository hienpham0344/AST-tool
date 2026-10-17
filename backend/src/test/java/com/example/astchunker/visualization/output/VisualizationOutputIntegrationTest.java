package com.example.astchunker.visualization.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.BreakpointMapper;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.VisualState;
import com.example.astchunker.visualization.VisualTraceBuilder;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the v1 output against observations captured from independently compiled target programs.
 */
class VisualizationOutputIntegrationTest {
  private static final String BUBBLE =
      """
      for (int pass = 0; pass < nums.length - 1; pass++) {
        for (int scan = 0; scan < nums.length - 1 - pass; scan++) {
          if (nums[scan] > nums[scan + 1]) {
            int temp = nums[scan];
            nums[scan] = nums[scan + 1];
            nums[scan + 1] = temp;
          }
        }
      }
      """;
  private static final String SELECTION =
      """
      for (int pass = 0; pass < nums.length - 1; pass++) {
        int min = pass;
        for (int scan = pass + 1; scan < nums.length; scan++) {
          if (nums[scan] < nums[min]) min = scan;
        }
        int temp = nums[pass];
        nums[pass] = nums[min];
        nums[min] = temp;
      }
      """;
  private static final String INSERTION =
      """
      for (int pass = 1; pass < nums.length; pass++) {
        int key = nums[pass];
        int scan = pass - 1;
        while (scan >= 0 && nums[scan] > key) {
          nums[scan + 1] = nums[scan];
          scan--;
        }
        nums[scan + 1] = key;
      }
      """;

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("sortPrograms")
  void normalizesObservedSortRuntimeForBothDirections(
      String type, String direction, String body, List<Integer> expected) {
    var trace = run("int[] nums = {3, 1, 2};\n" + body + "\nSystem.out.println(nums[0]);");

    var terminal =
        trace.steps().stream().filter(step -> step.code().startsWith("System.out")).findFirst();
    assertThat(terminal).isPresent();
    var normalizedTerminal = frameFor(trace, terminal.orElseThrow()).states().get(0);
    assertThat(normalizedTerminal.array().values()).containsExactlyElementsOf(expected);
    assertThat(normalizedTerminal.sortedRegion().status()).isEqualTo("VERIFIED_SORTED");
    assertThat(trace.output().algorithms())
        .singleElement()
        .satisfies(
            algorithm -> {
              assertThat(algorithm.type()).isEqualTo(type);
              assertThat(algorithm.sort().direction().name()).isEqualTo(direction);
            });
    assertThat(trace.output().frames())
        .anySatisfy(
            frame ->
                assertThat(frame.states())
                    .anySatisfy(
                        state ->
                            assertThat(state.operations())
                                .anySatisfy(
                                    operation -> {
                                      assertThat(operation.type()).isEqualTo("MUTATION");
                                      assertThat(operation.lifecycle()).isIn("PENDING", "OBSERVED");
                                      assertThat(operation.provenance().source())
                                          .isEqualTo("SortFrame");
                                    })));
    assertSortOperationContract(type, trace);
  }

  static Stream<Arguments> sortPrograms() {
    return Stream.of(
        Arguments.of("bubble-sort", "ASCENDING", BUBBLE, List.of(1, 2, 3)),
        Arguments.of(
            "bubble-sort",
            "DESCENDING",
            BUBBLE.replace("nums[scan] > nums[scan + 1]", "nums[scan] < nums[scan + 1]"),
            List.of(3, 2, 1)),
        Arguments.of("selection-sort", "ASCENDING", SELECTION, List.of(1, 2, 3)),
        Arguments.of(
            "selection-sort",
            "DESCENDING",
            SELECTION.replace("nums[scan] < nums[min]", "nums[scan] > nums[min]"),
            List.of(3, 2, 1)),
        Arguments.of("insertion-sort", "ASCENDING", INSERTION, List.of(1, 2, 3)),
        Arguments.of(
            "insertion-sort",
            "DESCENDING",
            INSERTION.replace("nums[scan] > key", "nums[scan] < key"),
            List.of(3, 2, 1)));
  }

  @ParameterizedTest
  @MethodSource("nonSortPrograms")
  void emitsOneNormalizedFramePerRealObservationWithoutInventedOperations(
      String type, String body) {
    var trace = run(body);

    assertThat(trace.output().algorithms())
        .singleElement()
        .extracting(algorithm -> algorithm.type())
        .isEqualTo(type);
    assertThat(trace.output().frames()).hasSameSizeAs(trace.steps());
    assertThat(trace.output().frames())
        .allSatisfy(frame -> assertThat(frame.source().lineNumber()).isPositive());
    var normalizedStates =
        trace.output().frames().stream().flatMap(frame -> frame.states().stream()).toList();
    assertThat(normalizedStates).isNotEmpty();
    assertThat(normalizedStates).allSatisfy(state -> assertThat(state.operations()).isEmpty());
    assertNonSortRuntimeValues(type, body, trace);
  }

  static Stream<Arguments> nonSortPrograms() {
    return Stream.of(
        Arguments.of(
            "binary-search",
            """
            int[] nums = {1, 3, 5, 7};
            int lo = 0, hi = nums.length - 1, target = 5;
            while (lo <= hi) {
              int mid = lo + (hi - lo) / 2;
              if (nums[mid] == target) break;
              if (nums[mid] < target) lo = mid + 1;
              else hi = mid - 1;
            }
            """),
        Arguments.of(
            "two-pointers",
            """
            int[] nums = {1, 2, 3, 4};
            int left = 0, right = nums.length - 1;
            while (left < right) {
              int temp = nums[left];
              nums[left] = nums[right];
              nums[right] = temp;
              left++;
              right--;
            }
            """),
        Arguments.of(
            "sliding-window",
            """
            int[] nums = {1, 2, 3, 4};
            int total = 0, k = 2;
            for (int end = 0; end < nums.length; end++) {
              total += nums[end];
              if (end >= k) total -= nums[end - k];
            }
            """),
        Arguments.of(
            "sliding-window",
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
            """));
  }

  private void assertNonSortRuntimeValues(String type, String body, Trace trace) {
    if (type.equals("binary-search")) {
      var declaration =
          trace.steps().stream()
              .filter(step -> step.code().startsWith("int mid"))
              .findFirst()
              .orElseThrow();
      var declarationState = frameFor(trace, declaration).states().get(0);
      assertThat(declarationState.pointers().get("mid").status()).isEqualTo("MISSING");
      assertThat(declarationState.array().values()).containsExactly(1, 3, 5, 7);
      assertThat(declarationState.range()).isNotNull();
      assertThat(
              trace.steps().stream()
                  .filter(step -> step.code().startsWith("if (nums[mid] =="))
                  .map(step -> frameFor(trace, step).states().get(0).pointers().get("mid").index()))
          .containsExactly(1L, 2L);
    } else if (type.equals("two-pointers")) {
      assertThat(
              trace.output().frames().stream()
                  .flatMap(frame -> frame.changes().stream())
                  .filter(change -> change.type().equals("ARRAY_CHANGED")))
          .anySatisfy(
              change -> {
                assertThat(change.before()).isInstanceOf(VisualState.ArrayValue.class);
                assertThat(change.after()).isInstanceOf(VisualState.ArrayValue.class);
                assertThat(((VisualState.ArrayValue) change.before()).values())
                    .containsExactly(1, 2, 3, 4);
                assertThat(((VisualState.ArrayValue) change.after()).values())
                    .containsExactly(4, 2, 3, 4);
              });
    } else if (body.contains("begin")) {
      var removals =
          trace.steps().stream()
              .filter(step -> step.code().equals("total -= nums[begin];"))
              .toList();
      assertThat(removals)
          .extracting(step -> frameFor(trace, step).states().get(0).pointers().get("left").index())
          .containsExactly(0L, 1L, 2L);
      assertThat(removals)
          .extracting(
              step -> frameFor(trace, step).states().get(0).scalars().get("accumulator").value())
          .containsExactly(3, 5, 3);
    } else {
      var additions =
          trace.steps().stream().filter(step -> step.code().equals("total += nums[end];")).toList();
      assertThat(additions)
          .extracting(
              step -> frameFor(trace, step).states().get(0).scalars().get("accumulator").value())
          .containsExactly(0, 1, 3, 5);
      assertThat(additions)
          .allSatisfy(
              step ->
                  assertThat(frameFor(trace, step).states().get(0).pointers())
                      .doesNotContainKey("left"));
    }
  }

  private NormalizedVisualizationResponse.Frame frameFor(Trace trace, ExecutionObservation step) {
    return trace.output().frames().stream()
        .filter(frame -> frame.sequence() == step.sequence())
        .findFirst()
        .orElseThrow();
  }

  @org.junit.jupiter.api.Test
  void preservesDistinctNestedAlgorithmStatesInOneRealSnapshot() {
    var trace =
        run(
            """
            int[] nums = {1, 3, 5, 7};
            int left = 0, right = nums.length - 1, target = 5;
            while (left < right) {
              int pair = nums[left] + nums[right];
              int lo = left, hi = right;
              while (lo <= hi) {
                int mid = lo + (hi - lo) / 2;
                if (nums[mid] == target) break;
                if (nums[mid] < target) lo = mid + 1;
                else hi = mid - 1;
              }
              left++;
              right--;
            }
            """);

    assertThat(trace.output().algorithms())
        .extracting(NormalizedVisualizationResponse.Algorithm::type)
        .contains("two-pointers", "binary-search");
    assertThat(trace.output().frames())
        .anySatisfy(
            frame ->
                assertThat(frame.states())
                    .extracting(NormalizedVisualizationResponse.State::algorithmId)
                    .containsExactlyInAnyOrder("hint-0", "hint-1"));
  }

  @org.junit.jupiter.api.Test
  void retainsHintMetadataForAnUncalledHelperWithoutInventingMainStates() {
    var trace =
        runSource(
            """
            public class OutputSample {
              static void search(int[] nums) {
                int lo = 0, hi = nums.length - 1, target = 3;
                while (lo <= hi) {
                  int mid = lo + (hi - lo) / 2;
                  if (nums[mid] == target) break;
                  if (nums[mid] < target) lo = mid + 1;
                  else hi = mid - 1;
                }
              }
              public static void main(String[] args) { int value = 7; System.out.println(value); }
            }
            """);

    assertThat(trace.output().algorithms())
        .singleElement()
        .extracting(algorithm -> algorithm.type())
        .isEqualTo("binary-search");
    assertThat(trace.output().frames()).allSatisfy(frame -> assertThat(frame.states()).isEmpty());
  }

  @org.junit.jupiter.api.Test
  void retainsBothInvocationsOfTheSameRecognizedHelperWithoutCarryingStateForward() {
    var trace =
        runSource(
            """
            public class OutputSample {
              static void search(int[] nums) {
                int lo = 0, hi = nums.length - 1, target = 3;
                while (lo <= hi) {
                  int mid = lo + (hi - lo) / 2;
                  if (nums[mid] == target) break;
                  if (nums[mid] < target) lo = mid + 1;
                  else hi = mid - 1;
                }
              }
              public static void main(String[] args) {
                search(new int[] {1, 3, 5});
                search(new int[] {2, 3, 4});
              }
            }
            """);

    assertThat(trace.output().frames()).hasSameSizeAs(trace.steps());
    assertThat(trace.output().frames())
        .flatExtracting(NormalizedVisualizationResponse.Frame::states)
        .extracting(state -> state.array().values())
        .contains(List.of(1, 3, 5), List.of(2, 3, 4));
    assertThat(trace.output().frames())
        .flatExtracting(NormalizedVisualizationResponse.Frame::states)
        .extracting(state -> state.pointers().get("mid").status())
        .contains("MISSING");
  }

  private void assertSortOperationContract(String type, Trace trace) {
    var operations =
        trace.output().frames().stream()
            .flatMap(frame -> frame.states().stream())
            .flatMap(state -> state.operations().stream())
            .toList();
    assertThat(operations)
        .anySatisfy(
            operation -> {
              assertThat(operation.provenance().field()).isEqualTo("pendingMutation");
              assertThat(operation.lifecycle()).isEqualTo("PENDING");
              assertThat(operation.sourceStatus()).isEqualTo("PENDING_BEFORE_LOCATION");
            });
    assertThat(operations)
        .anySatisfy(
            operation -> {
              assertThat(operation.provenance().field()).isEqualTo("completedMutation");
              assertThat(operation.lifecycle()).isEqualTo("OBSERVED");
              assertThat(operation.sourceStatus()).isEqualTo("OBSERVED_AFTER_WRITE");
            });
    var comparisons =
        operations.stream().filter(operation -> operation.type().equals("COMPARISON")).toList();
    assertThat(comparisons).isNotEmpty();
    assertThat(comparisons)
        .allSatisfy(
            operation -> {
              assertThat(operation.lifecycle()).isEqualTo("UNKNOWN");
              assertThat(operation.provenance().field()).isEqualTo("comparison");
              assertThat(operation.sourceStatus()).isNotBlank();
            });
    if (type.equals("bubble-sort")) {
      var partialStep =
          trace.steps().stream()
              .filter(step -> step.code().startsWith("nums[scan + 1] = temp"))
              .findFirst()
              .orElseThrow();
      var partial = partialStep.visualStates().get(0);
      assertThat(partial.array().values())
          .containsExactlyElementsOf(
              trace.output().algorithms().get(0).sort().direction().name().equals("ASCENDING")
                  ? List.of(1, 1, 2)
                  : List.of(3, 2, 2));
      assertThat(partial.sortFrame().phase()).doesNotContain("SWAP_COMPLETED");
      assertThat(
              trace.output().frames().stream()
                  .filter(frame -> frame.sequence() == partialStep.sequence())
                  .flatMap(frame -> frame.states().stream())
                  .flatMap(state -> state.operations().stream())
                  .map(NormalizedVisualizationResponse.Operation::kind))
          .doesNotContain("SWAP_COMPLETED");
    } else if (type.equals("selection-sort")) {
      assertThat(operations)
          .anySatisfy(
              operation -> assertThat(operation.action()).isEqualTo("UPDATE_SELECTED_INDEX"));
    } else {
      assertThat(operations)
          .anySatisfy(
              operation -> {
                assertThat(operation.kind()).isEqualTo("SHIFT_RIGHT");
                assertThat(operation.lifecycle()).isEqualTo("PENDING");
              });
      assertThat(operations)
          .anySatisfy(
              operation -> {
                assertThat(operation.kind()).isEqualTo("INSERT_KEY");
                assertThat(operation.lifecycle()).isEqualTo("PENDING");
              });
      assertThat(operations)
          .anySatisfy(
              operation -> {
                assertThat(operation.kind()).isEqualTo("SHIFT_RIGHT_COMPLETED");
                assertThat(operation.lifecycle()).isEqualTo("OBSERVED");
              });
      assertThat(operations)
          .anySatisfy(
              operation -> {
                assertThat(operation.kind()).isEqualTo("INSERT_KEY_COMPLETED");
                assertThat(operation.lifecycle()).isEqualTo("OBSERVED");
              });
    }
  }

  private Trace run(String body) {
    String source =
        "public class OutputSample { public static void main(String[] args) {\n" + body + "\n} }";
    return runSource(source);
  }

  private Trace runSource(String source) {
    var analysis = new AstAnalyzer().analyze(source);
    List<AlgorithmHint> hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var runtime =
          new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
              .observe(target, analysis);
      // BreakpointMapper may intentionally de-duplicate AST points sharing one JDI location.
      assertThat(runtime.warnings())
          .allMatch(warning -> warning.startsWith("Multiple observation points"));
      assertThat(runtime.executions()).isNotEmpty();
      var steps = new VisualTraceBuilder().build(analysis, hints, runtime.executions());
      return new Trace(steps, new VisualizationOutputMapper().map(steps, hints));
    }
  }

  private record Trace(List<ExecutionObservation> steps, NormalizedVisualizationResponse output) {}
}
