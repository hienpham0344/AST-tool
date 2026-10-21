package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.Node;
import java.util.*;
import org.junit.jupiter.api.Test;

class DfsTraceBuilderTest {
  private final AstAnalyzer.Analysis analysis =
      new AstAnalyzer()
          .analyze(
              """
              class Sample {
                static void walk(int[][] graph, int current, boolean[] visited) {
                  int local = current + 10;
                  visited[current] = true;
                  for (int neighbor=0; neighbor<graph.length; neighbor++) {
                    if (graph[current][neighbor]!=0 && !visited[neighbor]) {
                      walk(graph, neighbor, visited);
                    }
                  }
                  int finished = current;
                }
                public static void main(String[] args) { int done = 1; }
              }
              """);
  private final List<AlgorithmHint> hints =
      new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
  private final AlgorithmHint hint =
      hints.stream().filter(h -> h.dfs() != null).findFirst().orElseThrow();
  private final Map<String, Node> nodes = new LinkedHashMap<>();

  {
    analysis.compilationUnit().walk(n -> nodes.put(AstAnalyzer.nodeId(n), n));
  }

  private Node node(String role) {
    return nodes.get(hint.dfs().operationAstNodeIds().get(role));
  }

  private Map<String, Object> graph(String status) {
    return new LinkedHashMap<>(
        Map.of(
            "vertexCount",
            3,
            "edges",
            List.of(Map.of("from", 0, "to", 1), Map.of("from", 1, "to", 2)),
            "status",
            status,
            "truncated",
            status.equals("TRUNCATED")));
  }

  private Map<String, Object> flags(List<?> values, String status) {
    return Map.of(
        "values",
        values,
        "length",
        values.size(),
        "status",
        status,
        "truncated",
        status.equals("TRUNCATED"));
  }

  private ObservationResult value(String role, Object value, Long id) {
    return new ObservationResult(
        hint.variableDeclarationIds().get(role),
        hint.variables().get(role),
        "var",
        "value",
        role.equals("graph") ? "bfs-graph" : role.equals("visited") ? "bfs-visited" : "primitive",
        value,
        4,
        id,
        id);
  }

  private ExecutionObservation step(
      Node point,
      int line,
      Map<String, Object> graph,
      Map<String, Object> flags,
      Integer current,
      Integer neighbor,
      String method,
      Long invocation) {
    List<ObservationResult> values = new ArrayList<>();
    if (graph != null) values.add(value("graph", graph, 10L));
    if (flags != null) values.add(value("visited", flags, 20L));
    if (current != null) values.add(value("current", current, null));
    if (neighbor != null) values.add(value("neighbor", neighbor, null));
    return new ExecutionObservation(
        1,
        AstAnalyzer.nodeId(point),
        line,
        line,
        line,
        1,
        80,
        "ExpressionStmt",
        point.toString(),
        values,
        new ExecutionContext(1, "thread", "Sample", method, "([[II[Z)V", 5, 0, invocation));
  }

  private ExecutionObservation step(
      String role,
      Map<String, Object> graph,
      Map<String, Object> flags,
      Integer current,
      Integer neighbor) {
    Node point = node(role);
    return step(
        point, point.getBegin().orElseThrow().line, graph, flags, current, neighbor, "walk", 7L);
  }

  private JsonNode state(ExecutionObservation step) {
    var states =
        new VisualTraceBuilder().build(analysis, hints, List.of(step)).get(0).visualStates();
    assertThat(states).singleElement();
    return new ObjectMapper().valueToTree(states.get(0));
  }

  private JsonNode frame(ExecutionObservation step) {
    JsonNode f = state(step).path("dfsFrame");
    assertThat(f.isMissingNode()).isFalse();
    return f;
  }

  @Test
  void beforeMarkDoesNotInventVisit() {
    var f =
        frame(
            step(
                "mark",
                graph("AVAILABLE"),
                flags(List.of(false, false, false), "AVAILABLE"),
                0,
                null));
    assertThat(f.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(f.path("visitedVertices").size()).isZero();
    assertThat(f.path("markStatus").asText()).isEqualTo("PENDING");
    assertThat(f.path("invocationId").asLong()).isEqualTo(7);
    assertThat(f.path("stackDepth").asInt()).isEqualTo(5);
    assertThat(f.path("snapshotPhase").asText()).isEqualTo("BEFORE_LOCATION");
  }

  @Test
  void observedFlagsAreASetNotDiscoveryOrder() {
    var f =
        frame(
            step(
                "neighborLoop",
                graph("AVAILABLE"),
                flags(List.of(true, false, true), "AVAILABLE"),
                2,
                null));
    assertThat(f.path("visitedVertices").toString()).isEqualTo("[0,2]");
    assertThat(f.path("markStatus").asText()).isEqualTo("OBSERVED");
  }

  @Test
  void callIsPendingAndProvenEdgeIsOnlyCandidate() {
    var f =
        frame(
            step(
                "recursiveCall",
                graph("AVAILABLE"),
                flags(List.of(true, false, false), "AVAILABLE"),
                0,
                1));
    assertThat(f.path("examiningEdge").path("to").asInt()).isEqualTo(1);
    assertThat(f.path("edgeStatus").asText()).isEqualTo("CANDIDATE");
    assertThat(f.path("callStatus").asText()).isEqualTo("PENDING");
    assertThat(f.path("visitedVertices").toString()).isEqualTo("[0]");
  }

  @Test
  void absentGraphEdgeNeverBecomesCandidate() {
    var f =
        frame(
            step(
                "recursiveCall",
                graph("AVAILABLE"),
                flags(List.of(true, false, false), "AVAILABLE"),
                0,
                2));
    assertThat(f.path("examiningEdge").isNull()).isTrue();
    assertThat(f.path("callStatus").asText()).isEqualTo("UNKNOWN");
  }

  @Test
  void unavailableAndNullVertexCountAreSafe() {
    var g = graph("UNKNOWN");
    g.put("vertexCount", null);
    var s = state(step("mark", g, null, 0, null));
    assertThat(s.path("status").asText()).isEqualTo("PARTIAL");
    assertThat(s.path("dfsFrame").path("graphStatus").asText()).isEqualTo("UNKNOWN");
    assertThat(s.path("dfsFrame").path("visitedVertices").size()).isZero();
  }

  @Test
  void truncatedDataPreservesUncertainty() {
    var s =
        state(
            step(
                "recursiveCall",
                graph("TRUNCATED"),
                flags(List.of(true, false, false), "AVAILABLE"),
                0,
                1));
    assertThat(s.path("status").asText()).isEqualTo("TRUNCATED");
    assertThat(s.path("dfsFrame").path("examiningEdge").isNull()).isTrue();
  }

  @Test
  void malformedFlagsDoNotConfirmAnyVisitOrEdge() {
    var f =
        frame(
            step(
                "guard",
                graph("AVAILABLE"),
                flags(List.of(true, "bad", false), "AVAILABLE"),
                0,
                1));
    assertThat(f.path("visitedVertices").size()).isZero();
    assertThat(f.path("visitedStatus").asText()).isEqualTo("UNKNOWN");
    assertThat(f.path("examiningEdge").isNull()).isTrue();
  }

  @Test
  void malformedGraphDoesNotConfirmEdge() {
    var g = graph("AVAILABLE");
    g.put("edges", List.of(Map.of("from", 0, "to", 1), Map.of("from", 1, "to", 9)));
    var f = frame(step("guard", g, flags(List.of(true, false, false), "AVAILABLE"), 0, 1));
    assertThat(f.path("graphStatus").asText()).isEqualTo("UNKNOWN");
    assertThat(f.path("examiningEdge").isNull()).isTrue();
  }

  @Test
  void mismatchedVisitedLengthCannotConfirmEdge() {
    var f =
        frame(step("guard", graph("AVAILABLE"), flags(List.of(true, false), "AVAILABLE"), 0, 1));
    assertThat(f.path("examiningEdge").isNull()).isTrue();
  }

  @Test
  void invalidIndicesAreExplicitAndDoNotCreateEdge() {
    for (int[] pair :
        List.of(new int[] {-1, 1}, new int[] {3, 1}, new int[] {0, -1}, new int[] {0, 3})) {
      var f =
          frame(
              step(
                  "guard",
                  graph("AVAILABLE"),
                  flags(List.of(true, false, false), "AVAILABLE"),
                  pair[0],
                  pair[1]));
      assertThat(f.path("examiningEdge").isNull()).isTrue();
      assertThat(f.path(pair[0] < 0 || pair[0] >= 3 ? "currentStatus" : "neighborStatus").asText())
          .isEqualTo("OUT_OF_BOUNDS");
    }
  }

  @Test
  void ancestorLoopHeaderIsNotPendingCallAndIgnoresExhaustedNeighbor() {
    Node loop = node("neighborLoop");
    var f =
        frame(
            step(
                loop,
                loop.getBegin().orElseThrow().line,
                graph("AVAILABLE"),
                flags(List.of(true, true, true), "AVAILABLE"),
                0,
                3,
                "walk",
                7L));
    assertThat(f.path("examiningEdge").isNull()).isTrue();
    assertThat(f.path("neighborStatus").asText()).isEqualTo("NOT_APPLICABLE");
    assertThat(f.path("callStatus").asText()).isEqualTo("NOT_APPLICABLE");
  }

  @Test
  void descendantLocationOnLoopCanStillMatchCallAnchor() {
    Node loop = node("neighborLoop");
    var f =
        frame(
            step(
                loop,
                node("recursiveCall").getBegin().orElseThrow().line,
                graph("AVAILABLE"),
                flags(List.of(true, false, false), "AVAILABLE"),
                0,
                1,
                "walk",
                7L));
    assertThat(f.path("callStatus").asText()).isEqualTo("PENDING");
  }

  @Test
  void wrongRuntimeMethodOrSourceCallableProducesNoState() {
    Node mark = node("mark");
    var wrong =
        step(
            mark,
            4,
            graph("AVAILABLE"),
            flags(List.of(true, false, false), "AVAILABLE"),
            0,
            1,
            "main",
            7L);
    assertThat(
            new VisualTraceBuilder().build(analysis, hints, List.of(wrong)).get(0).visualStates())
        .isEmpty();
    Node main =
        analysis
            .compilationUnit()
            .findAll(com.github.javaparser.ast.body.MethodDeclaration.class)
            .stream()
            .filter(m -> m.getNameAsString().equals("main"))
            .findFirst()
            .orElseThrow()
            .getBody()
            .orElseThrow()
            .getStatement(0);
    var s =
        step(
            main,
            main.getBegin().orElseThrow().line,
            graph("AVAILABLE"),
            flags(List.of(true, false, false), "AVAILABLE"),
            0,
            1,
            "main",
            7L);
    assertThat(new VisualTraceBuilder().build(analysis, hints, List.of(s)).get(0).visualStates())
        .isEmpty();
  }

  @Test
  void missingInvocationOrCurrentRemainsPartial() {
    Node mark = node("mark");
    var s =
        state(
            step(
                mark,
                4,
                graph("AVAILABLE"),
                flags(List.of(false, false, false), "AVAILABLE"),
                0,
                null,
                "walk",
                null));
    assertThat(s.path("status").asText()).isEqualTo("PARTIAL");
    assertThat(
            state(
                    step(
                        "mark",
                        graph("AVAILABLE"),
                        flags(List.of(false, false, false), "AVAILABLE"),
                        null,
                        null))
                .path("status")
                .asText())
        .isEqualTo("PARTIAL");
  }

  @Test
  void truncatedVisitedCannotExposeVerticesOutsideTheGraph() {
    List<Boolean> values = new ArrayList<>(Collections.nCopies(100, false));
    values.set(99, true);
    Map<String, Object> oversized = new LinkedHashMap<>(flags(values, "TRUNCATED"));
    oversized.put("length", 101);
    var f = frame(step("mark", graph("AVAILABLE"), oversized, 0, null));
    assertThat(f.path("visitedStatus").asText()).isEqualTo("UNKNOWN");
    assertThat(f.path("visitedVertices").size()).isZero();
    assertThat(f.path("examiningEdge").isNull()).isTrue();
  }

  @Test
  void truncatedMalformedFlagsRemainUnknownRatherThanPartiallyTrusted() {
    var f =
        frame(
            step(
                "mark",
                graph("AVAILABLE"),
                flags(List.of(true, "bad", false), "TRUNCATED"),
                0,
                null));
    assertThat(f.path("visitedStatus").asText()).isEqualTo("UNKNOWN");
    assertThat(f.path("visitedVertices").size()).isZero();
  }
}
