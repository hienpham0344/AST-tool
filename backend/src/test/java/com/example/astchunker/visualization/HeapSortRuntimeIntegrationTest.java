package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.BreakpointMapper;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.ExecutionObservation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class HeapSortRuntimeIntegrationTest {
  private static final String SOURCE;

  static {
    try {
      SOURCE = Files.readString(Path.of("../examples/sort/HeapSortSample.java"));
    } catch (java.io.IOException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private List<ExecutionObservation> trace(String source) {
    AstAnalyzer parser = new AstAnalyzer();
    var analysis = parser.analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints)
        .singleElement()
        .satisfies(
            hint -> {
              assertThat(hint.type()).isEqualTo("heap-sort");
              assertThat(hint.heap()).isNotNull();
            });
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var observed =
          new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
              .observe(target, analysis);
      assertThat(observed.warnings()).noneMatch(warning -> warning.contains("timed out"));
      return new VisualTraceBuilder().build(analysis, hints, observed.executions());
    }
  }

  @Test
  void reportsPendingThenObservedSwapsAndSortedResults() {
    var steps = trace(SOURCE);
    var frames =
        steps.stream()
            .flatMap(step -> step.visualStates().stream())
            .map(state -> state.heapFrame())
            .filter(Objects::nonNull)
            .toList();
    assertThat(frames).isNotEmpty();
    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.pendingMutation()).isNotNull();
              assertThat(frame.pendingMutation().kind()).isEqualTo("ROOT_SWAP_PENDING");
            });
    assertThat(frames).anyMatch(frame -> "VERIFIED_HEAP".equals(frame.heapStatus()));
    assertThat(
            steps.stream()
                .filter(step -> step.code().startsWith("int saved = data[0]"))
                .flatMap(step -> step.visualStates().stream())
                .filter(state -> Integer.valueOf(3).equals(state.array().length()))
                .map(state -> state.heapFrame().heapEnd()))
        .contains(3);
    assertThat(frames)
        .filteredOn(frame -> frame.pendingMutation() != null)
        .allSatisfy(frame -> assertThat(frame.heapStatus()).isEqualTo("UNKNOWN"));
    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.completedMutation()).isNotNull();
              assertThat(frame.completedMutation().kind()).isEqualTo("ROOT_SWAP_COMPLETED");
            });
    var comparisons =
        frames.stream().map(frame -> frame.comparison()).filter(Objects::nonNull).toList();
    assertThat(comparisons)
        .isNotEmpty()
        .allSatisfy(
            comparison -> {
              int right = ((Number) comparison.firstValue()).intValue();
              int left = ((Number) comparison.secondValue()).intValue();
              assertThat(comparison.actionRequired()).isEqualTo(right > left);
            });
    var endings =
        steps.stream().filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings)
        .hasSize(6)
        .extracting(step -> step.visualStates().get(0).heapFrame().fullArrayStatus())
        .containsOnly("VERIFIED_SORTED");
    assertThat(endings)
        .extracting(step -> step.visualStates().get(0).array().values())
        .containsExactly(
            List.of(),
            List.of(7),
            List.of(1, 2, 3),
            List.of(1, 2, 3),
            List.of(1, 2, 2, 4, 4),
            List.of(-3, -1, 0, 2));
  }

  @Test
  void descendingHeapUsesMinHeapAndVerifiesFinalOrder() {
    String source =
        SOURCE
            .replace("values[right] > values[left]", "values[left] > values[right]")
            .replace("values[cursor] >= values[chosen]", "values[chosen] >= values[cursor]");
    var endings =
        trace(source).stream()
            .filter(step -> step.code().startsWith("System.out.println"))
            .toList();
    assertThat(endings)
        .hasSize(6)
        .extracting(step -> step.visualStates().get(0).heapFrame().fullArrayStatus())
        .containsOnly("VERIFIED_SORTED");
    assertThat(endings)
        .extracting(step -> step.visualStates().get(0).array().values())
        .containsExactly(
            List.of(),
            List.of(7),
            List.of(3, 2, 1),
            List.of(3, 2, 1),
            List.of(4, 4, 2, 2, 1),
            List.of(2, 0, -1, -3));
  }

  @Test
  void inlinedRestorationStillUsesObservedArrayAndVerifiesResults() {
    String sift =
        """
        while (2 * cursor + 1 < limit) {
          int left = 2 * cursor + 1;
          int right = left + 1;
          int chosen = left;
          if (right < limit && data[right] > data[left]) chosen = right;
          if (data[cursor] >= data[chosen]) break;
          int temp = data[cursor];
          data[cursor] = data[chosen];
          data[chosen] = temp;
          cursor = chosen;
        }
        """;
    String source =
        SOURCE
            .replace(
                "sift(data, size, start);", "int cursor = start; " + sift.replace("limit", "size"))
            .replace("sift(data, end, 0);", "int cursor = 0; " + sift.replace("limit", "end"));
    var steps = trace(source);
    assertThat(
            steps.stream()
                .flatMap(step -> step.visualStates().stream())
                .map(state -> state.heapFrame())
                .filter(Objects::nonNull))
        .anySatisfy(frame -> assertThat(frame.comparison()).isNotNull());
    assertThat(steps.stream().filter(step -> step.code().startsWith("System.out.println")))
        .hasSize(6)
        .allSatisfy(
            step ->
                assertThat(step.visualStates().get(0).heapFrame().fullArrayStatus())
                    .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void topDownBuildExposesSiftUpComparisonsAndSwaps() {
    String source =
        SOURCE
            .replace(
                "for (int start = size / 2 - 1; start >= 0; start--) {",
                "for (int start = 1; start < size; start++) {")
            .replace("sift(data, size, start);", "rise(data, size, start);")
            .replace(
                "  private static void sift(int[] values, int limit, int cursor) {",
                """
                  private static void rise(int[] values, int limit, int cursor) {
                    while (cursor > 0) {
                      int parent = (cursor - 1) / 2;
                      if (values[parent] >= values[cursor]) break;
                      int saved = values[parent];
                      values[parent] = values[cursor];
                      values[cursor] = saved;
                      cursor = parent;
                    }
                  }
                  private static void sift(int[] values, int limit, int cursor) {
                """);
    var steps = trace(source);
    var frames =
        steps.stream()
            .flatMap(step -> step.visualStates().stream())
            .map(state -> state.heapFrame())
            .filter(Objects::nonNull)
            .toList();
    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.phase()).isEqualTo("SIFT_UP");
              assertThat(frame.comparison()).isNotNull();
            });
    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.phase()).isEqualTo("SIFT_UP");
              assertThat(frame.completedMutation()).isNotNull();
            });
    assertThat(steps.stream().filter(step -> step.code().startsWith("System.out.println")))
        .hasSize(6)
        .allSatisfy(
            step ->
                assertThat(step.visualStates().get(0).heapFrame().fullArrayStatus())
                    .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void recursiveSiftComparesObservedChildWithCurrentChoice() {
    int bodyStart = SOURCE.indexOf("    while (2 * cursor + 1 < limit)");
    int bodyEnd = SOURCE.indexOf("\n  }\n}", bodyStart);
    assertThat(bodyStart).isGreaterThan(0);
    assertThat(bodyEnd).isGreaterThan(bodyStart);
    String source =
        SOURCE.substring(0, bodyStart)
            + """
                int left = 2 * cursor + 1;
                int right = left + 1;
                int chosen = cursor;
                if (left < limit && values[left] > values[chosen]) chosen = left;
                if (right < limit && values[right] > values[chosen]) chosen = right;
                if (chosen != cursor) {
                  int saved = values[cursor];
                  values[cursor] = values[chosen];
                  values[chosen] = saved;
                  sift(values, limit, chosen);
                }
                """
            + SOURCE.substring(bodyEnd);
    var steps = trace(source);
    assertThat(
            steps.stream()
                .filter(
                    step ->
                        step.visualStates().stream()
                            .anyMatch(
                                state ->
                                    state.heapFrame() != null
                                        && state.heapFrame().comparison() != null))
                .map(ExecutionObservation::statementAstNodeId)
                .distinct())
        .hasSize(2);
    assertThat(
            steps.stream()
                .flatMap(step -> step.visualStates().stream())
                .map(state -> state.heapFrame())
                .filter(Objects::nonNull)
                .map(frame -> frame.comparison())
                .filter(Objects::nonNull))
        .isNotEmpty()
        .allSatisfy(
            comparison -> {
              int first = ((Number) comparison.firstValue()).intValue();
              int second = ((Number) comparison.secondValue()).intValue();
              assertThat(comparison.actionRequired()).isEqualTo(first > second);
            });
  }

  @Test
  void repeatedCallsOnTheSameArrayKeepIndependentInvocations() {
    String source = SOURCE.replace("      sort(data);", "      sort(data);\n      sort(data);");
    var steps = trace(source);
    var endings =
        steps.stream().filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings)
        .hasSize(6)
        .allSatisfy(
            step ->
                assertThat(step.visualStates().get(0).heapFrame().fullArrayStatus())
                    .isEqualTo("VERIFIED_SORTED"));
    assertThat(
            steps.stream()
                .flatMap(step -> step.visualStates().stream())
                .map(state -> state.heapFrame())
                .filter(Objects::nonNull)
                .map(frame -> frame.invocationId())
                .filter(Objects::nonNull)
                .distinct()
                .count())
        .isGreaterThan(6);
  }
}
