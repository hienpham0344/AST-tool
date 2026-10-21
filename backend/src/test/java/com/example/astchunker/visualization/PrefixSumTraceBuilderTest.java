package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrefixSumTraceBuilderTest {
  static final String SOURCE =
      """
      class Sample {
        static int build(int[] values, int left, int right) {
          int[] sums = new int[values.length + 1];
          sums[0] = 0;
          for (int cursor = 0; cursor < values.length; cursor++) {
            sums[cursor + 1] = sums[cursor] + values[cursor];
          }
          int answer = sums[right + 1] - sums[left];
          return answer;
        }
      }
      """;
  AstAnalyzer.Analysis analysis;
  List<AlgorithmHint> hints;
  AlgorithmHint hint;

  PrefixSumTraceBuilderTest() {
    initialize(SOURCE);
  }

  void initialize(String source) {
    analysis = new AstAnalyzer().analyze(source);
    hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    hint = hints.stream().filter(h -> h.prefixSum() != null).findFirst().orElseThrow();
  }

  PrefixSumTraceBuilderTest fixture(String source) {
    var f = new PrefixSumTraceBuilderTest();
    f.initialize(source);
    return f;
  }

  Node node(String id) {
    return analysis.compilationUnit().findAll(Node.class).stream()
        .filter(n -> AstAnalyzer.nodeId(n).equals(id))
        .findFirst()
        .orElseThrow();
  }

  Node operation(String role) {
    return node(hint.prefixSum().operationAstNodeIds().get(role));
  }

  Node query() {
    return node(hint.prefixSum().queries().get(0).expressionAstNodeId());
  }

  ObservationResult variable(String role, Object value, Long identity, int line) {
    return new ObservationResult(
        hint.variableDeclarationIds().get(role),
        hint.variables().get(role),
        role.equals("index") ? "int" : "int[]",
        String.valueOf(value),
        role.equals("index") ? "primitive" : "array",
        value,
        line,
        identity,
        identity);
  }

  ExecutionObservation step(
      Node point,
      Object input,
      Object output,
      Integer index,
      Long sourceId,
      Long resultId,
      Long invocation) {
    int line = point.getBegin().orElseThrow().line;
    List<ObservationResult> vars = new ArrayList<>();
    if (input != null) vars.add(variable("data", input, sourceId, line));
    if (output != null) vars.add(variable("prefix", output, resultId, line));
    if (index != null) vars.add(variable("index", index, null, line));
    for (String endpoint : List.of("left", "right")) {
      var declaration =
          analysis.variables().stream()
              .filter(v -> v.name().equals(endpoint))
              .findFirst()
              .orElseThrow();
      vars.add(
          new ObservationResult(
              declaration.astNodeId(), endpoint, "int", "0", "primitive", 0, line));
    }
    return new ExecutionObservation(
        1,
        AstAnalyzer.nodeId(point),
        line,
        line,
        line,
        1,
        100,
        point.getClass().getSimpleName(),
        point.toString(),
        vars,
        new ExecutionContext(1, "main", "Sample", "build", "([III)I", 1, 0, invocation));
  }

  JsonNode frame(ExecutionObservation step) {
    var enriched = new VisualTraceBuilder().build(analysis, hints, List.of(step));
    JsonNode states = new ObjectMapper().valueToTree(enriched.get(0)).path("visualStates");
    assertThat(states.size()).isEqualTo(1);
    JsonNode f = states.get(0).path("prefixSumFrame");
    assertThat(f.isMissingNode()).isFalse();
    return f;
  }

  JsonNode recurrence(Object input, Object output, Integer index) {
    return frame(step(operation("recurrence"), input, output, index, 10L, 20L, 7L));
  }

  @Test
  void pendingRecurrenceDoesNotConfirmDefaultZero() {
    JsonNode f = recurrence(List.of(0, -3, 3), List.of(0, 0, 0, 0), 0);
    assertThat(f.path("sourceArrayObjectId").asLong()).isEqualTo(10);
    assertThat(f.path("resultArrayObjectId").asLong()).isEqualTo(20);
    assertThat(f.path("sourceIndex").asLong()).isZero();
    assertThat(f.path("previousPrefixIndex").asLong()).isZero();
    assertThat(f.path("targetIndex").asLong()).isEqualTo(1);
    assertThat(f.path("sourceValue").asInt()).isZero();
    assertThat(f.path("previousPrefixValue").asInt()).isZero();
    assertThat(f.path("recurrenceStatus").asText()).isEqualTo("PENDING");
    assertThat(f.path("confirmedPrefixEndExclusive").asLong()).isEqualTo(1);
    assertThat(f.path("confirmedCells").size()).isEqualTo(1);
    assertThat(f.path("snapshotPhase").asText()).isEqualTo("BEFORE_LOCATION");
  }

  @Test
  void completedZeroIsConfirmedByIndexRatherThanValue() {
    JsonNode f = recurrence(List.of(0, -3, 3), List.of(0, 0, 0, 0), 1);
    assertThat(f.path("confirmedPrefixEndExclusive").asLong()).isEqualTo(2);
    assertThat(f.path("confirmedCells").get(1).path("value").asInt()).isZero();
    assertThat(f.path("confirmedCells").get(1).path("status").asText()).isEqualTo("CONFIRMED");
  }

  @Test
  void explicitSeedIsPending() {
    JsonNode f =
        frame(step(operation("seed"), List.of(2, 3, 4), List.of(0, 0, 0, 0), null, 10L, 20L, 7L));
    assertThat(f.path("seedStatus").asText()).isEqualTo("PENDING");
    assertThat(f.path("confirmedPrefixEndExclusive").asLong()).isZero();
    assertThat(f.path("confirmedCells").size()).isZero();
  }

  @Test
  void missingArrayAndIndexStayUnknown() {
    JsonNode f = recurrence(List.of(2, 3, 4), null, 1);
    assertThat(f.path("result").path("status").asText()).isEqualTo("UNKNOWN");
    assertThat(f.path("previousPrefixValue").isNull()).isTrue();
    assertThat(f.path("confirmedPrefixEndExclusive").isNull()).isTrue();
    assertThat(f.path("progressStatus").asText()).isEqualTo("UNKNOWN");
    assertThat(
            recurrence(List.of(2, 3, 4), List.of(0, 2, 0, 0), null)
                .path("recurrenceStatus")
                .asText())
        .isEqualTo("UNKNOWN");
  }

  @Test
  void truncationDoesNotInventMissingAddends() {
    JsonNode f =
        recurrence(List.of(2, 3, 4), List.of(0, 2, Map.of("truncated", true, "length", 4)), 2);
    assertThat(f.path("result").path("status").asText()).isEqualTo("TRUNCATED");
    assertThat(f.path("recurrenceStatus").asText()).isEqualTo("NOT_CAPTURED");
    assertThat(f.path("previousPrefixValue").isNull()).isTrue();
    assertThat(f.path("confirmedCells").size()).isEqualTo(2);
    assertThat(f.path("progressStatus").asText()).isEqualTo("TRUNCATED");
  }

  @Test
  void outOfBoundsDoesNotReadOrConfirmCells() {
    JsonNode f = recurrence(List.of(2, 3, 4), List.of(0, 2, 5, 9), 4);
    assertThat(f.path("recurrenceStatus").asText()).isEqualTo("OUT_OF_BOUNDS");
    assertThat(f.path("sourceValue").isNull()).isTrue();
    assertThat(f.path("confirmedPrefixEndExclusive").isNull()).isTrue();
  }

  @Test
  void identityAndInvocationAreRequiredForProgress() {
    for (var s :
        List.of(
            step(operation("recurrence"), List.of(2, 3, 4), List.of(0, 2, 5, 9), 2, 10L, 20L, null),
            step(
                operation("recurrence"), List.of(2, 3, 4), List.of(0, 2, 5, 9), 2, 10L, 10L, 7L))) {
      JsonNode f = frame(s);
      assertThat(f.path("progressStatus").asText()).isEqualTo("UNKNOWN");
      assertThat(f.path("confirmedCells").size()).isZero();
    }
  }

  @Test
  void boundedQueryIsPendingWithObservedAddends() {
    JsonNode f = frame(step(query(), List.of(2, 3, 4), List.of(0, 2, 5, 9), null, 10L, 20L, 7L));
    assertThat(f.path("confirmedPrefixEndExclusive").asLong()).isEqualTo(4);
    assertThat(f.path("query").path("status").asText()).isEqualTo("PENDING");
    assertThat(f.path("query").path("upperPrefixIndex").asLong()).isEqualTo(1);
    assertThat(f.path("query").path("upperValue").asInt()).isEqualTo(2);
    assertThat(f.path("query").path("lowerValue").asInt()).isZero();
    assertThat(f.path("query").path("result").isNull()).isTrue();
  }

  @Test
  void queryBoundsPreventInventedSubtraction() {
    JsonNode f = frame(step(query(), List.of(), List.of(0), null, 10L, 20L, 7L));
    assertThat(f.path("query").path("status").asText()).isEqualTo("OUT_OF_BOUNDS");
    assertThat(f.path("query").path("result").isNull()).isTrue();
  }

  @Test
  void followingQueryStatementUsesActualResult() {
    Node returned = analysis.compilationUnit().findFirst(ReturnStmt.class).orElseThrow();
    var original = step(returned, List.of(2, 3, 4), List.of(0, 2, 5, 9), null, 10L, 20L, 7L);
    var vars = new ArrayList<>(original.variables());
    var answer =
        analysis.variables().stream()
            .filter(v -> v.name().equals("answer"))
            .findFirst()
            .orElseThrow();
    vars.add(
        new ObservationResult(
            answer.astNodeId(), "answer", "int", "2", "primitive", 2, original.lineNumber()));
    var s =
        new ExecutionObservation(
            1,
            original.statementAstNodeId(),
            original.lineNumber(),
            original.startLine(),
            original.endLine(),
            1,
            100,
            "ReturnStmt",
            returned.toString(),
            vars,
            original.context());
    assertThat(frame(s).path("query").path("status").asText()).isEqualTo("OBSERVED");
    assertThat(frame(s).path("query").path("result").asInt()).isEqualTo(2);
  }

  @Test
  void malformedProjectionDoesNotEstablishProgress() {
    JsonNode f = recurrence(List.of(2, 3, 4), List.of(0, 2, "bad", 9), 2);
    assertThat(f.path("result").path("status").asText()).isEqualTo("UNKNOWN");
    assertThat(f.path("progressStatus").asText()).isEqualTo("UNKNOWN");
    assertThat(f.path("confirmedPrefixEndExclusive").isNull()).isTrue();
    assertThat(f.path("confirmedCells").size()).isZero();
  }

  @Test
  void queryAssigningEndpointCannotReuseChangedEndpointAsOriginalOperand() {
    var fixture =
        fixture(
            SOURCE
                .replace(
                    "int answer = sums[right + 1] - sums[left];",
                    "right = sums[right + 1] - sums[left];")
                .replace("return answer;", "return right;"));
    var returned = fixture.analysis.compilationUnit().findFirst(ReturnStmt.class).orElseThrow();
    var original =
        fixture.step(returned, List.of(2, 3, 4), List.of(0, 2, 5, 9), null, 10L, 20L, 7L);
    var vars =
        original.variables().stream()
            .map(
                v ->
                    v.variableName().equals("right")
                        ? new ObservationResult(
                            v.astNodeId(),
                            "right",
                            "int",
                            "2",
                            "primitive",
                            2,
                            original.lineNumber())
                        : v)
            .toList();
    var observed =
        new ExecutionObservation(
            1,
            original.statementAstNodeId(),
            original.lineNumber(),
            original.startLine(),
            original.endLine(),
            1,
            100,
            "ReturnStmt",
            returned.toString(),
            vars,
            original.context());
    JsonNode q = fixture.frame(observed).path("query");
    assertThat(q.path("status").asText()).isEqualTo("UNKNOWN");
    assertThat(q.path("right").isNull()).isTrue();
    assertThat(q.path("upperValue").isNull()).isTrue();
    assertThat(q.path("result").isNull()).isTrue();
  }

  @Test
  void compoundAssignmentResultIsNotTheSubtractionExpressionValue() {
    var fixture =
        fixture(
            SOURCE.replace(
                "int answer = sums[right + 1] - sums[left];",
                "int answer = 100;\n    answer += sums[right + 1] - sums[left];"));
    var returned = fixture.analysis.compilationUnit().findFirst(ReturnStmt.class).orElseThrow();
    var original =
        fixture.step(returned, List.of(2, 3, 4), List.of(0, 2, 5, 9), null, 10L, 20L, 7L);
    var vars = new ArrayList<>(original.variables());
    var answer =
        fixture.analysis.variables().stream()
            .filter(v -> v.name().equals("answer"))
            .findFirst()
            .orElseThrow();
    vars.add(
        new ObservationResult(
            answer.astNodeId(), "answer", "int", "102", "primitive", 102, original.lineNumber()));
    var observed =
        new ExecutionObservation(
            1,
            original.statementAstNodeId(),
            original.lineNumber(),
            original.startLine(),
            original.endLine(),
            1,
            100,
            "ReturnStmt",
            returned.toString(),
            vars,
            original.context());
    assertThat(fixture.frame(observed).path("query").path("status").asText()).isEqualTo("UNKNOWN");
    assertThat(fixture.frame(observed).path("query").path("result").isNull()).isTrue();
  }

  @Test
  void loopHeaderIsNotThePendingRecurrenceSourceSite() {
    JsonNode f =
        frame(step(operation("loop"), List.of(2, 3, 4), List.of(0, 2, 5, 0), 2, 10L, 20L, 7L));
    assertThat(f.path("recurrenceStatus").asText()).isEqualTo("NOT_AT_RECURRENCE");
    assertThat(f.path("confirmedPrefixEndExclusive").asLong()).isEqualTo(3);
  }

  @Test
  void wrongCallableContextNeverAttachesFrame() {
    var original =
        step(operation("recurrence"), List.of(2, 3, 4), List.of(0, 2, 5, 0), 2, 10L, 20L, 7L);
    for (var context :
        List.of(
            new ExecutionContext(1, "main", "Other", "build", "([III)I", 1, 0, 7L),
            new ExecutionContext(1, "main", "Sample", "other", "([III)I", 1, 0, 7L))) {
      var changed =
          new ExecutionObservation(
              1,
              original.statementAstNodeId(),
              original.lineNumber(),
              original.startLine(),
              original.endLine(),
              1,
              100,
              "AssignExpr",
              original.code(),
              original.variables(),
              context);
      assertThat(
              new VisualTraceBuilder()
                  .build(analysis, hints, List.of(changed))
                  .get(0)
                  .visualStates())
          .isEmpty();
    }
  }

  @Test
  void freshInvocationAndResultIdentityDoNotInheritEarlierProgress() {
    var prior =
        step(operation("recurrence"), List.of(2, 3, 4), List.of(0, 2, 5, 0), 2, 10L, 20L, 7L);
    var fresh =
        step(operation("recurrence"), List.of(0, 3, 4), List.of(0, 0, 0, 0), 0, 11L, 21L, 8L);
    var built = new VisualTraceBuilder().build(analysis, hints, List.of(prior, fresh));
    var f = built.get(1).visualStates().get(0).prefixSumFrame();
    assertThat(f.invocationId()).isEqualTo(8);
    assertThat(f.resultArrayObjectId()).isEqualTo(21);
    assertThat(f.confirmedPrefixEndExclusive()).isEqualTo(1);
    assertThat(f.confirmedCells()).hasSize(1);
  }

  @Test
  void normalLoopTerminationIsNotAnOutOfBoundsRecurrence() {
    JsonNode f =
        frame(step(operation("loop"), List.of(2, 3, 4), List.of(0, 2, 5, 9), 3, 10L, 20L, 7L));
    assertThat(f.path("recurrenceStatus").asText()).isEqualTo("NOT_AT_RECURRENCE");
    assertThat(f.path("confirmedPrefixEndExclusive").asLong()).isEqualTo(4);
  }

  private JsonNode multiDeclaratorQuery(String declaration, boolean after, int left, int answer) {
    var fixture =
        fixture(SOURCE.replace("int answer = sums[right + 1] - sums[left];", declaration));
    assertThat(fixture.hint.prefixSum().queries()).hasSize(1);
    Node point =
        after
            ? fixture.analysis.compilationUnit().findFirst(ReturnStmt.class).orElseThrow()
            : fixture.query();
    var original = fixture.step(point, List.of(2, 3, 4), List.of(0, 2, 5, 9), null, 10L, 20L, 7L);
    var variables = new ArrayList<ObservationResult>();
    for (var v : original.variables()) {
      int endpoint = v.variableName().equals("left") ? left : 2;
      variables.add(
          v.variableName().equals("left") || v.variableName().equals("right")
              ? new ObservationResult(
                  v.astNodeId(),
                  v.variableName(),
                  "int",
                  String.valueOf(endpoint),
                  "primitive",
                  endpoint,
                  original.lineNumber())
              : v);
    }
    if (after) {
      var target =
          fixture.analysis.variables().stream()
              .filter(v -> v.name().equals("answer"))
              .findFirst()
              .orElseThrow();
      variables.add(
          new ObservationResult(
              target.astNodeId(),
              "answer",
              "int",
              String.valueOf(answer),
              "primitive",
              answer,
              original.lineNumber()));
    }
    var step =
        new ExecutionObservation(
            1,
            original.statementAstNodeId(),
            original.lineNumber(),
            original.startLine(),
            original.endLine(),
            1,
            100,
            original.statementKind(),
            original.code(),
            variables,
            original.context());
    return fixture.frame(step).path("query");
  }

  @Test
  void siblingEndpointMutationAfterQuerySuppressesPendingAndObservedQueryFrames() {
    String declaration = "int answer = sums[right + 1] - sums[left], shifted = left++;";
    assertThat(multiDeclaratorQuery(declaration, false, 0, 9).isNull()).isTrue();
    assertThat(multiDeclaratorQuery(declaration, true, 1, 9).isNull()).isTrue();
  }

  @Test
  void siblingEndpointMutationBeforeQuerySuppressesPendingAndObservedQueryFrames() {
    String declaration = "int shifted = left++, answer = sums[right + 1] - sums[left];";
    assertThat(multiDeclaratorQuery(declaration, false, 0, 7).isNull()).isTrue();
    assertThat(multiDeclaratorQuery(declaration, true, 1, 7).isNull()).isTrue();
  }

  @Test
  void siblingOverwriteOfQueryResultSuppressesPendingAndObservedQueryFrames() {
    String declaration = "int answer = sums[right + 1] - sums[left], ignored = (answer = 99);";
    assertThat(multiDeclaratorQuery(declaration, false, 0, 99).isNull()).isTrue();
    assertThat(multiDeclaratorQuery(declaration, true, 0, 99).isNull()).isTrue();
  }
}
