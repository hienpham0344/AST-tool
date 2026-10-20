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
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TwoPointersTraceBuilderTest {
  private final AstAnalyzer.Analysis analysis = new AstAnalyzer().analyze("""
      class Sample {
        void run(int[] data) {
          int left = 0, right = data.length - 1;
          while (left < right) {
            int sum = data[left] + data[right];
            left++;
            right--;
          }
        }
      }
      """);
  private final List<AlgorithmHint> hints =
      new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
  private final Map<String, Node> nodes = nodes();

  private Map<String, Node> nodes() {
    Map<String, Node> result = new LinkedHashMap<>();
    analysis.compilationUnit().walk(n -> result.put(AstAnalyzer.nodeId(n), n));
    return result;
  }

  private Node statement(String code) {
    return analysis.compilationUnit().findAll(ExpressionStmt.class).stream()
        .filter(s -> s.toString().trim().equals(code)).findFirst().orElseThrow();
  }

  private ExecutionObservation step(long sequence, Node point, long invocation,
                                    long objectId, int left, int right, List<Integer> data) {
    return step(sequence, point, 1, invocation, objectId, left, right, data);
  }

  private ExecutionObservation step(long sequence, Node point, long thread,
                                    long invocation, long objectId, int left, int right,
                                    List<Integer> data) {
    AlgorithmHint hint = hints.get(0);
    return new ExecutionObservation(sequence, AstAnalyzer.nodeId(point),
        point.getBegin().orElseThrow().line, point.getBegin().orElseThrow().line,
        point.getEnd().orElseThrow().line, 1, 30, "ExpressionStmt", point.toString(),
        List.of(
            new ObservationResult(hint.variableDeclarationIds().get("data"), "data", "int[]",
                data.toString(), "array", data, point.getBegin().orElseThrow().line,
                objectId, objectId),
            new ObservationResult(hint.variableDeclarationIds().get("left"), "left", "int",
                String.valueOf(left), "primitive", left, point.getBegin().orElseThrow().line),
            new ObservationResult(hint.variableDeclarationIds().get("right"), "right", "int",
                String.valueOf(right), "primitive", right, point.getBegin().orElseThrow().line)),
        new ExecutionContext(thread, "main", "Sample", "run", "([I)V", 1, sequence, invocation));
  }

  @Test
  void confirmsOnlyAfterASecondSnapshotOfTheSameInvocation() {
    assertThat(hints).singleElement().satisfies(h -> assertThat(h.type()).isEqualTo("two-pointers"));
    var trace = new TwoPointersTraceBuilder(hints, nodes);
    Node leftUpdate = statement("left++;");
    Node rightUpdate = statement("right--;");
    var before = step(1, leftUpdate, 7, 99, 0, 2, List.of(2, 5, 8));
    var after = step(2, rightUpdate, 7, 99, 1, 2, List.of(2, 5, 8));
    assertThat(trace.states(before, leftUpdate, "Sample")).singleElement()
        .satisfies(state -> assertThat(state.twoPointersFrame().effectStatus()).isEqualTo("PENDING"));
    assertThat(trace.states(after, rightUpdate, "Sample")).singleElement()
        .satisfies(state -> {
          assertThat(state.twoPointersFrame().effectStatus()).isEqualTo("OBSERVED");
          assertThat(state.twoPointersFrame().firstDelta()).isEqualTo(1L);
        });
  }

  @Test
  void doesNotConfirmAcrossInvocationOrObjectIdentity() {
    Node leftUpdate = statement("left++;");
    Node rightUpdate = statement("right--;");
    var trace = new TwoPointersTraceBuilder(hints, nodes);
    trace.states(step(1, leftUpdate, 7, 99, 0, 2, List.of(2, 5, 8)), leftUpdate, "Sample");
    assertThat(trace.states(step(2, rightUpdate, 8, 99, 1, 2, List.of(2, 5, 8)),
        rightUpdate, "Sample")).singleElement().satisfies(state ->
            assertThat(state.twoPointersFrame().effectStatus()).isNotEqualTo("OBSERVED"));
    assertThat(trace.states(step(3, rightUpdate, 7, 100, 1, 2, List.of(2, 5, 8)),
        rightUpdate, "Sample")).singleElement().satisfies(state ->
            assertThat(state.twoPointersFrame().effectStatus()).isNotEqualTo("OBSERVED"));
  }

  @Test
  void marksObservedOutwardMovementAsViolation() {
    Node leftUpdate = statement("left++;");
    Node rightUpdate = statement("right--;");
    var trace = new TwoPointersTraceBuilder(hints, nodes);
    trace.states(step(1, leftUpdate, 7, 99, 0, 2, List.of(2, 5, 8)), leftUpdate, "Sample");
    assertThat(trace.states(step(2, rightUpdate, 7, 99, -1, 2, List.of(2, 5, 8)),
        rightUpdate, "Sample")).singleElement().satisfies(state ->
            assertThat(state.twoPointersFrame().effectStatus()).isEqualTo("VIOLATED"));
  }

  @Test
  void doesNotInventAnIntermediateMoveWhenSnapshotsSkipAnUpdate() {
    Node leftUpdate = statement("left++;");
    Node rightUpdate = statement("right--;");
    var trace = new TwoPointersTraceBuilder(hints, nodes);
    trace.states(step(1, leftUpdate, 7, 99, 0, 3, List.of(2, 5, 8, 9)),
        leftUpdate, "Sample");
    assertThat(trace.states(step(3, rightUpdate, 7, 99, 2, 3, List.of(2, 5, 8, 9)),
        rightUpdate, "Sample")).singleElement().satisfies(state ->
            assertThat(state.twoPointersFrame().effectStatus()).isEqualTo("UNKNOWN"));
  }

  @Test
  void doesNotConfirmAcrossThreadsEvenWithMatchingInvocationAndObjectIds() {
    Node leftUpdate = statement("left++;");
    Node rightUpdate = statement("right--;");
    var trace = new TwoPointersTraceBuilder(hints, nodes);
    trace.states(step(1, leftUpdate, 1, 7, 99, 0, 2, List.of(2, 5, 8)),
        leftUpdate, "Sample");
    assertThat(trace.states(step(2, rightUpdate, 2, 7, 99, 1, 2, List.of(2, 5, 8)),
        rightUpdate, "Sample")).singleElement().satisfies(state ->
            assertThat(state.twoPointersFrame().effectStatus()).isNotEqualTo("OBSERVED"));
  }

  @Test
  void keepsEmptyAndOutOfBoundsSnapshotsUnknown() {
    Node leftUpdate = statement("left++;");
    var trace = new TwoPointersTraceBuilder(hints, nodes);
    assertThat(trace.states(step(1, leftUpdate, 7, 99, 0, -1, List.of()),
        leftUpdate, "Sample")).singleElement().satisfies(state -> {
          assertThat(state.twoPointersFrame().effectStatus()).isEqualTo("UNKNOWN");
          assertThat(state.pointers().get("right").status()).isEqualTo("OUT_OF_BOUNDS");
          assertThat(state.array().values()).isEmpty();
        });
  }

  @Test
  void doesNotConfirmAPendingMoveAcrossAnUnavailableInputSnapshot() {
    Node leftUpdate = statement("left++;");
    Node rightUpdate = statement("right--;");
    var trace = new TwoPointersTraceBuilder(hints, nodes);
    trace.states(step(1, leftUpdate, 7, 99, 0, 2, List.of(2, 5, 8)),
        leftUpdate, "Sample");
    var middle = step(2, rightUpdate, 7, 99, 0, 2, List.of(2, 5, 8));
    var variables = new ArrayList<>(middle.variables());
    ObservationResult input = variables.get(0);
    variables.set(0, new ObservationResult(input.astNodeId(), input.variableName(),
        input.declaredType(), "[unavailable]", "unavailable", null,
        middle.lineNumber(), 99L, 99L));
    var unavailable = new ExecutionObservation(middle.sequence(), middle.statementAstNodeId(),
        middle.lineNumber(), middle.startLine(), middle.endLine(), middle.startColumn(),
        middle.endColumn(), middle.statementKind(), middle.code(), variables, middle.context());
    assertThat(trace.states(unavailable, rightUpdate, "Sample")).singleElement().satisfies(
        state -> assertThat(state.twoPointersFrame().effectStatus()).isEqualTo("UNKNOWN"));
    assertThat(trace.states(step(3, rightUpdate, 7, 99, 1, 2, List.of(2, 5, 8)),
        rightUpdate, "Sample")).singleElement().satisfies(state ->
            assertThat(state.twoPointersFrame().effectStatus()).isEqualTo("PENDING"));
  }
}
