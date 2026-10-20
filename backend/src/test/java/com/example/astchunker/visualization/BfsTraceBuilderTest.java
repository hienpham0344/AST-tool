package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.*;
import com.github.javaparser.ast.Node;
import java.util.*;
import org.junit.jupiter.api.Test;

class BfsTraceBuilderTest {
  private final AstAnalyzer.Analysis analysis =
      new AstAnalyzer()
          .analyze(
              """
              import java.util.*;
              class Sample {
                void walk(int[][] graph) {
                  boolean[] visited = new boolean[graph.length];
                  Queue<Integer> queue = new ArrayDeque<>();
                  visited[0] = true;
                  queue.offer(0);
                  while (!queue.isEmpty()) {
                    int current = queue.poll();
                    for (int neighbor = 0; neighbor < graph.length; neighbor++) {
                      if (graph[current][neighbor] != 0 && !visited[neighbor]) {
                        visited[neighbor] = true;
                        queue.offer(neighbor);
                      }
                    }
                  }
                }
              }
              """);
  private final List<AlgorithmHint> hints =
      new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
  private final AlgorithmHint hint =
      hints.stream().filter(h -> h.bfs() != null).findFirst().orElseThrow();
  private final Map<String, Node> nodes = nodes();
  private final Node mark = nodes.get(hint.bfs().operationAstNodeIds().get("mark"));

  private Map<String, Node> nodes() {
    Map<String, Node> result = new LinkedHashMap<>();
    analysis.compilationUnit().walk(n -> result.put(AstAnalyzer.nodeId(n), n));
    return result;
  }

  private Map<String, Object> payload(List<?> values, String status) {
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

  private ExecutionObservation step(
      long sequence,
      List<Integer> queue,
      List<Boolean> visited,
      String status,
      long invocation,
      long thread,
      long queueId,
      boolean scalar,
      Node point) {
    int line = point.getBegin().orElseThrow().line;
    Map<String, Object> graph =
        Map.of(
            "vertexCount",
            3,
            "edges",
            List.of(Map.of("from", 0, "to", 1), Map.of("from", 1, "to", 2)),
            "status",
            "AVAILABLE",
            "truncated",
            false);
    List<ObservationResult> values = new ArrayList<>();
    values.add(value("graph", "bfs-graph", graph, 10L, line));
    values.add(value("queue", "bfs-queue", payload(queue, status), queueId, line));
    values.add(value("visited", "bfs-visited", payload(visited, "AVAILABLE"), 30L, line));
    if (scalar) {
      values.add(value("current", "primitive", 0, null, line));
      values.add(value("neighbor", "primitive", 1, null, line));
    }
    return new ExecutionObservation(
        sequence,
        AstAnalyzer.nodeId(point),
        line,
        line,
        line,
        1,
        60,
        "ExpressionStmt",
        point.toString(),
        values,
        new ExecutionContext(
            thread, "thread", "Sample", "walk", "([[I)V", 1, sequence, invocation));
  }

  private ObservationResult value(String role, String type, Object value, Long identity, int line) {
    return new ObservationResult(
        hint.variableDeclarationIds().get(role),
        hint.variables().get(role),
        role.equals("visited") ? "boolean[]" : "var",
        "value",
        type,
        value,
        line,
        identity,
        identity);
  }

  private ExecutionObservation enriched(
      BfsTraceBuilder builder, ExecutionObservation step, Node point) {
    return step.withVisualization(builder.states(step, point, "Sample"), List.of());
  }

  @Test
  void frameUsesObservedQueueAndVisitedBeforeLocation() {
    var builder = new BfsTraceBuilder(hints, nodes);
    var step = step(1, List.of(2), List.of(true, false, false), "AVAILABLE", 7, 1, 20, true, mark);
    assertThat(builder.states(step, mark, "Sample"))
        .singleElement()
        .satisfies(
            state -> {
              var frame = state.bfsFrame();
              assertThat(frame.queue()).containsExactly(2);
              assertThat(frame.visitedVertices()).containsExactly(0);
              assertThat(frame.currentVertex()).isZero();
              assertThat(frame.source()).isZero();
              assertThat(frame.examiningEdge()).containsEntry("from", 0).containsEntry("to", 1);
              assertThat(frame.snapshotPhase()).isEqualTo("BEFORE_LOCATION");
            });
  }

  @Test
  void eventsRequireObservedDifferencesRatherThanPendingStatements() {
    var builder = new BfsTraceBuilder(hints, nodes);
    var before =
        enriched(
            builder,
            step(1, List.of(2), List.of(true, false, false), "AVAILABLE", 7, 1, 20, true, mark),
            mark);
    var unchanged =
        enriched(
            builder,
            step(2, List.of(2), List.of(true, false, false), "AVAILABLE", 7, 1, 20, true, mark),
            mark);
    assertThat(builder.events(before, unchanged, unchanged.visualStates())).isEmpty();
    var marked =
        enriched(
            builder,
            step(3, List.of(2), List.of(true, true, false), "AVAILABLE", 7, 1, 20, true, mark),
            mark);
    assertThat(builder.events(unchanged, marked, marked.visualStates()))
        .extracting(VisualEvent::type)
        .containsExactly("VISIT");
    var queued =
        enriched(
            builder,
            step(4, List.of(2, 1), List.of(true, true, false), "AVAILABLE", 7, 1, 20, true, mark),
            mark);
    assertThat(builder.events(marked, queued, queued.visualStates()))
        .extracting(VisualEvent::type)
        .containsExactly("ENQUEUE");
    var dequeued =
        enriched(
            builder,
            step(5, List.of(1), List.of(true, true, false), "AVAILABLE", 7, 1, 20, true, mark),
            mark);
    assertThat(builder.events(queued, dequeued, dequeued.visualStates()))
        .extracting(VisualEvent::type)
        .containsExactly("DEQUEUE");
  }

  @Test
  void missingScalarsAndEmptyQueueDoNotInventCompletionOrEdges() {
    var builder = new BfsTraceBuilder(hints, nodes);
    Node seed = nodes.get(hint.bfs().operationAstNodeIds().get("seed"));
    var step = step(1, List.of(), List.of(true, false, false), "AVAILABLE", 7, 1, 20, false, seed);
    assertThat(builder.states(step, seed, "Sample"))
        .singleElement()
        .satisfies(
            state -> {
              assertThat(state.status()).isNotEqualTo("COMPLETED");
              assertThat(state.bfsFrame().currentVertex()).isNull();
              assertThat(state.bfsFrame().examiningEdge()).isNull();
            });
  }

  @Test
  void threadInvocationAndObjectReplacementBreakEventContinuity() {
    var builder = new BfsTraceBuilder(hints, nodes);
    var before =
        enriched(
            builder,
            step(1, List.of(0), List.of(true, false, false), "AVAILABLE", 7, 1, 20, true, mark),
            mark);
    for (var changed :
        List.of(
            step(2, List.of(), List.of(true, false, false), "AVAILABLE", 8, 1, 20, true, mark),
            step(2, List.of(), List.of(true, false, false), "AVAILABLE", 7, 2, 20, true, mark),
            step(2, List.of(), List.of(true, false, false), "AVAILABLE", 7, 1, 21, true, mark))) {
      var current = enriched(builder, changed, mark);
      assertThat(builder.events(before, current, current.visualStates())).isEmpty();
    }
  }

  @Test
  void truncatedUnknownAndAmbiguousChangesHaveNoOperationEvents() {
    var builder = new BfsTraceBuilder(hints, nodes);
    var before =
        enriched(
            builder,
            step(1, List.of(0), List.of(true, false, false), "AVAILABLE", 7, 1, 20, true, mark),
            mark);
    for (var changed :
        List.of(
            step(2, List.of(), List.of(true, false, false), "TRUNCATED", 7, 1, 20, true, mark),
            step(2, List.of(), List.of(true, false, false), "UNKNOWN", 7, 1, 20, true, mark),
            step(
                2,
                List.of(1, 2, 0),
                List.of(true, true, true),
                "AVAILABLE",
                7,
                1,
                20,
                true,
                mark))) {
      var current = enriched(builder, changed, mark);
      assertThat(builder.events(before, current, current.visualStates())).isEmpty();
    }
  }
}
