package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionContext;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.ObservationResult;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlidingWindowTraceBuilderTest {
  private final AstAnalyzer.Analysis analysis = new AstAnalyzer().analyze("""
      class Sample { void run(int[] values, int width) {
        if (width <= 0) return;
        int total = 0;
        for (int scan = 0; scan < values.length; scan++) {
          total += values[scan];
          if (scan >= width) total -= values[scan-width];
        }
      } }
      """);
  private final List<AlgorithmHint> hints =
      new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
  private final Map<String, Node> nodes = nodes();

  private Map<String, Node> nodes() {
    Map<String, Node> result = new LinkedHashMap<>();
    analysis.compilationUnit().walk(node -> result.put(AstAnalyzer.nodeId(node), node));
    return result;
  }

  private Node statement(String fragment) {
    return analysis.compilationUnit().findAll(ExpressionStmt.class).stream()
        .filter(node -> node.toString().contains(fragment)).findFirst().orElseThrow();
  }

  private ExecutionObservation step(long sequence, Node point, long thread, long invocation,
                                    long object, int scan, int total, Object data,
                                    String dataType) {
    AlgorithmHint hint = hints.get(0);
    int line = point.getBegin().orElseThrow().line;
    List<ObservationResult> values = new ArrayList<>();
    values.add(new ObservationResult(hint.variableDeclarationIds().get("data"), "values", "int[]",
        String.valueOf(data), dataType, data, line, object, object));
    values.add(new ObservationResult(hint.variableDeclarationIds().get("right"), "scan", "int",
        String.valueOf(scan), "primitive", scan, line));
    values.add(new ObservationResult(hint.variableDeclarationIds().get("accumulator"), "total", "int",
        String.valueOf(total), "primitive", total, line));
    values.add(new ObservationResult(hint.variableDeclarationIds().get("windowSize"), "width", "int",
        "2", "primitive", 2, line));
    return new ExecutionObservation(sequence, AstAnalyzer.nodeId(point), line, line, line,
        1, 60, "ExpressionStmt", point.toString(), values,
        new ExecutionContext(thread, "main", "Sample", "run", "([II)V", 1, sequence,
            invocation));
  }

  @Test
  void confirmsAnAddOnlyAfterASameContextSnapshot() {
    assertThat(hints).singleElement().satisfies(hint ->
        assertThat(hint.slidingWindow().kind()).isEqualTo("FIXED_SIZE"));
    Node add = statement("total +=");
    Node remove = statement("total -=");
    var trace = new SlidingWindowTraceBuilder(hints, nodes);
    assertThat(trace.states(step(1, add, 1, 7, 99, 0, 0, List.of(3, 5), "array"),
        add, "Sample")).singleElement().satisfies(state ->
            assertThat(state.slidingWindowFrame().effectStatus()).isEqualTo("PENDING"));
    assertThat(trace.states(step(2, remove, 1, 7, 99, 0, 3, List.of(3, 5), "array"),
        remove, "Sample")).singleElement().satisfies(state -> {
          assertThat(state.slidingWindowFrame().effectStatus()).isEqualTo("OBSERVED");
          assertThat(state.slidingWindowFrame().accumulator()).isEqualTo(3L);
        });
  }

  @Test
  void doesNotConfirmAcrossObjectsOrUnavailableInput() {
    Node add = statement("total +=");
    Node remove = statement("total -=");
    var trace = new SlidingWindowTraceBuilder(hints, nodes);
    trace.states(step(1, add, 1, 7, 99, 0, 0, List.of(3, 5), "array"), add, "Sample");
    assertThat(trace.states(step(2, remove, 1, 7, 100, 0, 3, List.of(3, 5), "array"),
        remove, "Sample")).singleElement().satisfies(state ->
            assertThat(state.slidingWindowFrame().effectStatus()).isNotEqualTo("OBSERVED"));
    assertThat(trace.states(step(3, remove, 1, 7, 99, 0, 3, null, "unavailable"),
        remove, "Sample")).singleElement().satisfies(state ->
            assertThat(state.slidingWindowFrame().effectStatus()).isEqualTo("UNKNOWN"));
  }

  @Test
  void isolatesThreadsAndInvocationsAndDoesNotBridgeSkippedSnapshots() {
    Node add = statement("total +=");
    Node remove = statement("total -=");
    var trace = new SlidingWindowTraceBuilder(hints, nodes);
    trace.states(step(1, add, 1, 7, 99, 0, 0, List.of(3, 5), "array"), add, "Sample");
    assertThat(trace.states(step(2, remove, 2, 7, 99, 0, 3, List.of(3, 5), "array"),
        remove, "Sample")).singleElement().satisfies(state ->
            assertThat(state.slidingWindowFrame().effectStatus()).isNotEqualTo("OBSERVED"));
    assertThat(trace.states(step(3, remove, 1, 8, 99, 0, 3, List.of(3, 5), "array"),
        remove, "Sample")).singleElement().satisfies(state ->
            assertThat(state.slidingWindowFrame().effectStatus()).isNotEqualTo("OBSERVED"));
    assertThat(trace.states(step(4, remove, 1, 7, 99, 0, 3, List.of(3, 5), "array"),
        remove, "Sample")).singleElement().satisfies(state ->
            assertThat(state.slidingWindowFrame().effectStatus()).isEqualTo("UNKNOWN"));
  }

  @Test
  void zeroEntryAndTruncatedInputCannotConfirmAnAddition() {
    Node add = statement("total +=");
    Node remove = statement("total -=");
    var trace = new SlidingWindowTraceBuilder(hints, nodes);
    trace.states(step(1, add, 1, 7, 99, 0, 0, List.of(0, 5), "array"), add, "Sample");
    assertThat(trace.states(step(2, remove, 1, 7, 99, 0, 0, List.of(0, 5), "array"),
        remove, "Sample")).singleElement().satisfies(state ->
            assertThat(state.slidingWindowFrame().effectStatus()).isNotEqualTo("OBSERVED"));
    assertThat(trace.states(step(3, remove, 1, 7, 99, 0, 0,
        List.of(0, Map.of("truncated", true, "length", 200)), "array"),
        remove, "Sample")).singleElement().satisfies(state ->
            assertThat(state.slidingWindowFrame().effectStatus()).isEqualTo("TRUNCATED"));
  }

  @Test
  void pointerJumpCannotConfirmAnUnobservedAddition() {
    Node add = statement("total +=");
    Node remove = statement("total -=");
    var trace = new SlidingWindowTraceBuilder(hints, nodes);
    trace.states(step(1, add, 1, 7, 99, 0, 0, List.of(3, 5, 8), "array"),
        add, "Sample");
    assertThat(trace.states(step(2, remove, 1, 7, 99, 2, 3,
        List.of(3, 5, 8), "array"), remove, "Sample"))
        .singleElement().satisfies(state ->
            assertThat(state.slidingWindowFrame().effectStatus()).isNotEqualTo("OBSERVED"));
  }
}
