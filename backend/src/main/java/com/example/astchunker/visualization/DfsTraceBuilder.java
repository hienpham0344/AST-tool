package com.example.astchunker.visualization;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.model.*;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.*;

/** Snapshot-only DFS rendering. Candidate edges and pending calls never imply execution. */
final class DfsTraceBuilder {
  private record Binding(
      int index, AlgorithmHint hint, MethodDeclaration method, Node mark, Node guard, Node call) {}

  private record Data(Map<String, Object> payload, String status) {}

  private final List<Binding> bindings;

  DfsTraceBuilder(List<AlgorithmHint> hints, Map<String, Node> nodes) {
    List<Binding> found = new ArrayList<>();
    for (int i = 0; i < hints.size(); i++) {
      AlgorithmHint h = hints.get(i);
      if (!"dfs".equals(h.type()) || h.dfs() == null) continue;
      if (!(nodes.get(h.methodAstNodeId()) instanceof MethodDeclaration method)
          || nodes.get(h.patternAstNodeId()) != method) continue;
      var ids = h.dfs().operationAstNodeIds();
      Node mark = nodes.get(ids.get("mark")),
          guard = nodes.get(ids.get("guard")),
          call = nodes.get(ids.get("recursiveCall"));
      if (mark != null && guard != null && call != null)
        found.add(new Binding(i, h, method, mark, guard, call));
    }
    bindings = List.copyOf(found);
  }

  List<VisualState> states(ExecutionObservation step, Node point, String mainClass) {
    if (point == null || step.context() == null || !mainClass.equals(step.context().className()))
      return List.of();
    List<VisualState> states = new ArrayList<>();
    for (Binding b : bindings) {
      if (LocalVariableBindings.callable(point) != b.method()
          || !b.method().getNameAsString().equals(step.context().methodName())) continue;
      states.add(state(b, step, point));
    }
    return List.copyOf(states);
  }

  private VisualState state(Binding b, ExecutionObservation step, Node point) {
    ObservationResult graphValue = value(b.hint(), step, "graph"),
        visitedValue = value(b.hint(), step, "visited");
    Data graph = data(graphValue, "bfs-graph"), visited = data(visitedValue, "bfs-visited");
    Integer vertices = integer(graph.payload().get("vertexCount"));
    boolean validGraph =
        vertices != null && vertices >= 0 && validEdges(graph.payload().get("edges"), vertices);
    String graphStatus =
        "AVAILABLE".equals(graph.status()) && !validGraph ? "UNKNOWN" : graph.status();
    Object flags = visited.payload().get("values");
    Integer length = integer(visited.payload().get("length"));
    boolean validFlags =
        flags instanceof List<?> list
            && list.stream().allMatch(Boolean.class::isInstance)
            && length != null
            && length >= list.size()
            && vertices != null
            && length.equals(vertices);
    if ("AVAILABLE".equals(visited.status()))
      validFlags = validFlags && length.equals(((List<?>) flags).size());
    // A truncated buffer still has to describe this graph's vertex domain.
    String visitedStatus = validFlags ? visited.status() : "UNKNOWN";
    List<Integer> seen = new ArrayList<>();
    if (validFlags && !"UNKNOWN".equals(visitedStatus)) {
      List<?> list = (List<?>) flags;
      for (int i = 0; i < list.size(); i++) if (Boolean.TRUE.equals(list.get(i))) seen.add(i);
    }
    Integer current = integerValue(value(b.hint(), step, "current"));
    boolean guardLocation = at(step, point, b.guard()), callLocation = at(step, point, b.call());
    boolean neighborLocation = guardLocation || callLocation;
    Integer neighbor = neighborLocation ? integerValue(value(b.hint(), step, "neighbor")) : null;
    String currentStatus = indexStatus(current, vertices),
        neighborStatus = neighborLocation ? indexStatus(neighbor, vertices) : "NOT_APPLICABLE";
    boolean reliable =
        "AVAILABLE".equals(graphStatus)
            && "AVAILABLE".equals(visitedStatus)
            && identity(graphValue) != null
            && identity(visitedValue) != null;
    boolean edgeProven =
        reliable
            && "AVAILABLE".equals(currentStatus)
            && "AVAILABLE".equals(neighborStatus)
            && hasEdge(graph.payload().get("edges"), current, neighbor);
    Map<String, Integer> edge = edgeProven ? Map.of("from", current, "to", neighbor) : null;
    String edgeStatus = !neighborLocation ? "NOT_APPLICABLE" : edgeProven ? "CANDIDATE" : "UNKNOWN";
    String markStatus = "UNKNOWN";
    if ("AVAILABLE".equals(visitedStatus) && "AVAILABLE".equals(currentStatus)) {
      if (seen.contains(current)) markStatus = "OBSERVED";
      else if (at(step, point, b.mark())) markStatus = "PENDING";
    }
    String callStatus =
        !callLocation
            ? "NOT_APPLICABLE"
            : edgeProven && !((List<?>) flags).get(neighbor).equals(Boolean.TRUE)
                ? "PENDING"
                : "UNKNOWN";
    boolean ready =
        reliable
            && step.context().invocationId() != null
            && "AVAILABLE".equals(currentStatus)
            && (!neighborLocation || "AVAILABLE".equals(neighborStatus));
    boolean truncated = List.of(graphStatus, visitedStatus).contains("TRUNCATED");
    List<String> notes =
        List.of(
            "OBSERVED_BEFORE_LOCATION",
            "VISITED_VERTICES_ARE_NOT_DISCOVERY_ORDER",
            "CANDIDATE_EDGE_DOES_NOT_PROVE_RECURSIVE_CALL",
            "SNAPSHOT_DOES_NOT_PROVE_COMPLETION_OR_RECURSION_PATH");
    DfsFrame frame =
        new DfsFrame(
            1,
            step.context().invocationId(),
            step.context().stackDepth(),
            identity(graphValue),
            identity(visitedValue),
            b.hint().dfs().representation(),
            current,
            neighbor,
            graph.payload(),
            seen,
            edge,
            graphStatus,
            visitedStatus,
            currentStatus,
            neighborStatus,
            edgeStatus,
            markStatus,
            callStatus,
            step.snapshotPhase(),
            notes);
    return new VisualState(
        "hint-" + b.index(),
        b.index(),
        b.hint().type(),
        b.hint().visualPlan(),
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
        null,
        null,
        frame);
  }

  private static boolean at(ExecutionObservation step, Node point, Node anchor) {
    // BreakpointMapper can map a nested bytecode location to an enclosing loop.
    // The actual location line must match the operation, never merely its ancestor's range.
    return anchor.getBegin().map(p -> p.line == step.lineNumber()).orElse(false)
        && (within(point, anchor) || within(anchor, point));
  }

  private static boolean within(Node child, Node parent) {
    for (Node n = child; n != null; n = n.getParentNode().orElse(null))
      if (n == parent) return true;
    return false;
  }

  private static ObservationResult value(AlgorithmHint h, ExecutionObservation step, String role) {
    String id = h.dfs().declarationIds().get(role), name = h.variables().get(role);
    if (id == null || name == null) return null;
    var values =
        step.variables().stream()
            .filter(v -> id.equals(v.astNodeId()) && name.equals(v.variableName()))
            .toList();
    return values.size() == 1 ? values.get(0) : null;
  }

  private static Long identity(ObservationResult v) {
    return v == null ? null : v.objectId();
  }

  private static Integer integerValue(ObservationResult v) {
    return v == null || !"primitive".equals(v.visualType()) ? null : integer(v.visualValue());
  }

  private static Integer integer(Object v) {
    if (!(v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long))
      return null;
    long n = ((Number) v).longValue();
    return n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? (int) n : null;
  }

  private static String indexStatus(Integer n, Integer count) {
    if (n == null || count == null || count < 0) return "UNKNOWN";
    return n >= 0 && n < count ? "AVAILABLE" : "OUT_OF_BOUNDS";
  }

  private static boolean validEdges(Object value, int count) {
    if (!(value instanceof List<?> edges)) return false;
    for (Object item : edges) {
      if (!(item instanceof Map<?, ?> edge)) return false;
      Integer from = integer(edge.get("from")), to = integer(edge.get("to"));
      if (!"AVAILABLE".equals(indexStatus(from, count))
          || !"AVAILABLE".equals(indexStatus(to, count))) return false;
    }
    return true;
  }

  private static boolean hasEdge(Object value, Integer from, Integer to) {
    return ((List<?>) value)
        .stream()
            .anyMatch(
                item ->
                    item instanceof Map<?, ?> edge
                        && from.equals(integer(edge.get("from")))
                        && to.equals(integer(edge.get("to"))));
  }

  private static Data data(ObservationResult v, String type) {
    if (v == null || !type.equals(v.visualType()) || !(v.visualValue() instanceof Map<?, ?> raw))
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
}
