package com.example.astchunker.visualization;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.BfsFrame;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.ObservationResult;
import com.example.astchunker.model.VisualEvent;
import com.example.astchunker.model.VisualState;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds BFS states and only unambiguous differences between complete observed snapshots. */
final class BfsTraceBuilder {
  private record Binding(
      int index,
      AlgorithmHint hint,
      Node loop,
      Node method,
      Node seed,
      Node seedMark,
      Node guard) {}

  private record Data(Map<String, Object> payload, String status) {}

  private final List<Binding> bindings;

  BfsTraceBuilder(List<AlgorithmHint> hints, Map<String, Node> nodes) {
    List<Binding> found = new ArrayList<>();
    for (int i = 0; i < hints.size(); i++) {
      AlgorithmHint h = hints.get(i);
      if (!"bfs".equals(h.type()) || h.bfs() == null) continue;
      Node loop = nodes.get(h.patternAstNodeId());
      Node method = nodes.get(h.methodAstNodeId());
      Node seed = nodes.get(h.bfs().operationAstNodeIds().get("seed"));
      Node enqueue = nodes.get(h.bfs().operationAstNodeIds().get("enqueue"));
      Node guard = enqueue == null ? null : enqueue.findAncestor(IfStmt.class).orElse(null);
      if (loop != null && method != null && seed != null)
        found.add(new Binding(i, h, loop, method, seed, seedMark(seed), guard));
    }
    bindings = List.copyOf(found);
  }

  List<VisualState> states(ExecutionObservation step, Node point, String mainClass) {
    if (point == null || step.context() == null || !mainClass.equals(step.context().className()))
      return List.of();
    List<VisualState> states = new ArrayList<>();
    for (Binding b : bindings) {
      if (LocalVariableBindings.callable(point) != b.method()) continue;
      // Include seeding snapshots, while keeping other invocations and later statements out.
      boolean active =
          within(point, b.loop())
              || within(point, b.seed())
              || within(b.seed(), point)
              || within(point, b.seedMark())
              || within(b.seedMark(), point);
      if (active) states.add(state(b, step, point));
    }
    return List.copyOf(states);
  }

  private VisualState state(Binding b, ExecutionObservation step, Node point) {
    AlgorithmHint h = b.hint();
    ObservationResult graphValue = value(h, step, "graph");
    ObservationResult queueValue = value(h, step, "queue");
    ObservationResult visitedValue = value(h, step, "visited");
    Data graph = data(graphValue, "bfs-graph");
    Data queue = data(queueValue, "bfs-queue");
    Data visited = data(visitedValue, "bfs-visited");
    Integer vertices = integer(graph.payload().get("vertexCount"));
    List<Integer> queued = integers(queue.payload().get("values"));
    List<Integer> seen = new ArrayList<>();
    Object rawVisited = visited.payload().get("values");
    boolean validVisited = rawVisited instanceof List<?>;
    if (rawVisited instanceof List<?> list) {
      for (int i = 0; i < list.size(); i++) {
        if (!(list.get(i) instanceof Boolean)) validVisited = false;
        if (Boolean.TRUE.equals(list.get(i))) seen.add(i);
      }
    }
    Integer current = integerValue(value(h, step, "current"));
    Integer neighbor = integerValue(value(h, step, "neighbor"));
    Integer source =
        h.bfs().sourceLiteral() != null
            ? h.bfs().sourceLiteral()
            : integerValue(value(h, step, "source"));
    if (!within(point, b.loop())) current = null;
    if (!vertex(current, vertices)) current = null;
    if (!vertex(source, vertices)) source = null;
    Map<String, Integer> edge = null;
    if (within(point, b.guard()) && current != null && vertex(neighbor, vertices))
      edge = Map.of("from", current, "to", neighbor);
    Long graphId = identity(graphValue),
        queueId = identity(queueValue),
        visitedId = identity(visitedValue);
    boolean ready =
        graphId != null
            && queueId != null
            && visitedId != null
            && step.context().invocationId() != null
            && graph.status().equals("AVAILABLE")
            && queue.status().equals("AVAILABLE")
            && visited.status().equals("AVAILABLE")
            && vertices != null
            && vertices >= 0
            && validVisited
            && rawVisited instanceof List<?> list
            && list.size() == vertices
            && queued != null
            && queued.stream().allMatch(v -> vertex(v, vertices));
    boolean truncated =
        List.of(graph.status(), queue.status(), visited.status()).contains("TRUNCATED");
    List<String> notes =
        List.of("OBSERVED_BEFORE_LOCATION", "EMPTY_QUEUE_DOES_NOT_PROVE_COMPLETION");
    BfsFrame frame =
        new BfsFrame(
            step.context().invocationId(),
            graphId,
            queueId,
            visitedId,
            h.bfs().representation(),
            source,
            current,
            graph.payload(),
            queued == null ? List.of() : queued,
            seen,
            edge,
            graph.status(),
            queue.status(),
            visited.status(),
            step.snapshotPhase(),
            notes);
    return new VisualState(
        "hint-" + b.index(),
        b.index(),
        h.type(),
        h.visualPlan(),
        ready ? "READY" : truncated ? "TRUNCATED" : "PARTIAL",
        null,
        Map.of(),
        Map.of(),
        null,
        notes,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        frame);
  }

  List<VisualEvent> events(
      ExecutionObservation previous, ExecutionObservation step, List<VisualState> states) {
    if (previous == null
        || previous.context() == null
        || step.context() == null
        || previous.sequence() >= step.sequence()
        || previous.context().threadId() != step.context().threadId()
        || previous.context().invocationId() == null
        || !Objects.equals(previous.context().invocationId(), step.context().invocationId()))
      return List.of();
    List<VisualEvent> events = new ArrayList<>();
    for (VisualState state : states) {
      if (state.bfsFrame() == null || !"READY".equals(state.status())) continue;
      VisualState oldState =
          previous.visualStates().stream()
              .filter(
                  s ->
                      s.id().equals(state.id())
                          && s.bfsFrame() != null
                          && "READY".equals(s.status()))
              .findFirst()
              .orElse(null);
      if (oldState == null) continue;
      BfsFrame a = oldState.bfsFrame(), b = state.bfsFrame();
      if (!Objects.equals(a.graphObjectId(), b.graphObjectId())
          || !Objects.equals(a.queueObjectId(), b.queueObjectId())
          || !Objects.equals(a.visitedObjectId(), b.visitedObjectId())
          || !a.graph().equals(b.graph())) continue;
      if (b.queue().size() == a.queue().size() + 1
          && b.queue().subList(0, a.queue().size()).equals(a.queue()))
        events.add(event(state, "ENQUEUE", "queue", previous, step, a.queue(), b.queue()));
      else if (a.queue().size() == b.queue().size() + 1
          && a.queue().subList(1, a.queue().size()).equals(b.queue()))
        events.add(event(state, "DEQUEUE", "queue", previous, step, a.queue(), b.queue()));
      if (b.visitedVertices().size() == a.visitedVertices().size() + 1
          && b.visitedVertices().containsAll(a.visitedVertices()))
        events.add(
            event(
                state,
                "VISIT",
                "visited",
                previous,
                step,
                a.visitedVertices(),
                b.visitedVertices()));
    }
    return List.copyOf(events);
  }

  private static VisualEvent event(
      VisualState state,
      String type,
      String target,
      ExecutionObservation previous,
      ExecutionObservation step,
      Object before,
      Object after) {
    return new VisualEvent(
        state.id(), type, target, previous.sequence(), step.sequence(), before, after);
  }

  private static ObservationResult value(
      AlgorithmHint hint, ExecutionObservation step, String role) {
    String id = hint.variableDeclarationIds().get(role), name = hint.variables().get(role);
    if (id == null || name == null) return null;
    List<ObservationResult> values =
        step.variables().stream()
            .filter(v -> id.equals(v.astNodeId()) && name.equals(v.variableName()))
            .toList();
    return values.size() == 1 ? values.get(0) : null;
  }

  private static Long identity(ObservationResult value) {
    return value == null ? null : value.objectId();
  }

  private static Integer integerValue(ObservationResult value) {
    return value == null ? null : integer(value.visualValue());
  }

  private static Integer integer(Object value) {
    if (!(value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long)) return null;
    long n = ((Number) value).longValue();
    return n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? (int) n : null;
  }

  private static boolean vertex(Integer value, Integer vertices) {
    return value != null && vertices != null && value >= 0 && value < vertices;
  }

  private static List<Integer> integers(Object value) {
    if (!(value instanceof List<?> list)) return null;
    List<Integer> result = new ArrayList<>();
    for (Object item : list) {
      Integer n = integer(item);
      if (n == null) return null;
      result.add(n);
    }
    return List.copyOf(result);
  }

  private static Data data(ObservationResult observation, String expected) {
    if (observation == null
        || !expected.equals(observation.visualType())
        || !(observation.visualValue() instanceof Map<?, ?> raw))
      return new Data(Map.of(), "UNKNOWN");
    Map<String, Object> payload = new LinkedHashMap<>();
    raw.forEach(
        (key, value) -> {
          if (key instanceof String name && value != null) payload.put(name, value);
        });
    String status =
        payload.get("status") instanceof String s
                && List.of("AVAILABLE", "UNKNOWN", "TRUNCATED").contains(s)
            ? s
            : "UNKNOWN";
    if (Boolean.TRUE.equals(payload.get("truncated"))) status = "TRUNCATED";
    return new Data(Map.copyOf(payload), status);
  }

  private static Node seedMark(Node seed) {
    Node statement = seed.findAncestor(ExpressionStmt.class).orElse(null);
    if (statement != null && statement.getParentNode().orElse(null) instanceof BlockStmt block) {
      int index = block.getStatements().indexOf(statement);
      if (index > 0) return block.getStatement(index - 1);
    }
    return null;
  }

  private static boolean within(Node child, Node parent) {
    if (parent == null) return false;
    for (Node n = child; n != null; n = n.getParentNode().orElse(null))
      if (n == parent) return true;
    return false;
  }
}
