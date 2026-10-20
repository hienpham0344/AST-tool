package com.example.astchunker.visualization;

import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.LinearSearchFrame;
import com.example.astchunker.model.ObservationResult;
import com.example.astchunker.model.VisualState;
import com.example.astchunker.model.VisualState.ArrayValue;
import com.example.astchunker.model.VisualState.IndexRange;
import com.example.astchunker.model.VisualState.Pointer;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Independent candidate-comparison frames from declaration-bound JDI values. */
final class LinearSearchTraceBuilder {
  private record Binding(int index, AlgorithmHint hint, Node loop,
                         CallableDeclaration<?> method) {}
  private record Data(List<Object> values, Integer length, String status, boolean truncated) {}

  private final List<Binding> bindings;

  LinearSearchTraceBuilder(List<AlgorithmHint> hints, Map<String, Node> nodes) {
    List<Binding> found = new ArrayList<>();
    for (int index = 0; index < hints.size(); index++) {
      AlgorithmHint hint = hints.get(index);
      if (!"linear-search".equals(hint.type()) || hint.linearSearch() == null) continue;
      Node loop = nodes.get(hint.patternAstNodeId());
      Node method = nodes.get(hint.methodAstNodeId());
      if (loop != null && method instanceof CallableDeclaration<?> callable)
        found.add(new Binding(index, hint, loop, callable));
    }
    bindings = List.copyOf(found);
  }

  List<VisualState> states(ExecutionObservation step, Node point, String mainClass) {
    if (step.context() == null || point == null
        || !mainClass.equals(step.context().className())) return List.of();
    List<VisualState> result = new ArrayList<>();
    for (Binding binding : bindings)
      if (within(point, binding.loop())
          && binding.method().getNameAsString().equals(step.context().methodName()))
        result.add(state(binding, step));
    return List.copyOf(result);
  }

  private VisualState state(Binding binding, ExecutionObservation step) {
    AlgorithmHint hint = binding.hint();
    ObservationResult input = value(hint, step, "data");
    Data data = data(input);
    Long index = number(value(hint, step, "scan"));
    Long target = number(value(hint, step, "target"));
    Object current = index != null && index >= 0 && index < data.values().size()
        ? data.values().get(index.intValue()) : null;
    Long inputId = input == null ? null : input.objectId();
    Long invocation = step.context().invocationId();
    boolean complete = inputId != null && invocation != null
        && data.status().equals("AVAILABLE") && data.length() != null
        && index != null && index >= 0 && index < data.length()
        && current instanceof Number && target != null;
    String comparison = data.truncated() ? "TRUNCATED" : !complete ? "UNKNOWN"
        : ((Number) current).longValue() == target ? "MATCH" : "DIFFERENT";
    String pointerStatus = index == null ? "MISSING" : data.length() == null
        ? "ARRAY_UNAVAILABLE" : index < 0 || index >= data.length()
            ? "OUT_OF_BOUNDS" : index >= data.values().size() ? "NOT_CAPTURED" : "VALID";
    ArrayValue array = new ArrayValue(hint.variables().get("data"),
        hint.variableDeclarationIds().get("data"), data.status(), data.values(),
        data.length(), data.truncated());
    Pointer scan = new Pointer(hint.variables().get("scan"),
        hint.variableDeclarationIds().get("scan"), index, pointerStatus);
    IndexRange range = index != null && data.length() != null
        && index >= 0 && index < data.length()
        ? new IndexRange("SCANNED_PREFIX_CANDIDATE", 0L, index, index == 0 ? "EMPTY" : "VALID")
        : new IndexRange("SCANNED_PREFIX_CANDIDATE", null, null, "UNKNOWN");
    LinearSearchFrame frame = new LinearSearchFrame(invocation, inputId, index,
        data.length(), current, target, comparison, hint.linearSearch().evidence(),
        step.snapshotPhase());
    List<String> notes = List.of("COMPARISON_IS_CANDIDATE_BEFORE_LOCATION");
    return new VisualState("hint-" + binding.index(), binding.index(), hint.type(),
        hint.visualPlan(), complete ? "READY" : data.truncated() ? "TRUNCATED" : "PARTIAL",
        array, Map.of("scan", scan), Map.of(), range, notes,
        null, null, null, null, null, null, null, frame);
  }

  private static ObservationResult value(AlgorithmHint hint, ExecutionObservation step,
                                         String role) {
    String id = hint.variableDeclarationIds().get(role);
    String name = hint.variables().get(role);
    if (id == null || name == null) return null;
    List<ObservationResult> matches = step.variables().stream()
        .filter(variable -> id.equals(variable.astNodeId())
            && name.equals(variable.variableName())).toList();
    return matches.size() == 1 ? matches.get(0) : null;
  }

  private static Long number(ObservationResult observation) {
    return observation != null && observation.visualValue() instanceof Number number
        && (number instanceof Byte || number instanceof Short
            || number instanceof Integer || number instanceof Long)
        ? number.longValue() : null;
  }

  private static Data data(ObservationResult input) {
    if (input == null) return new Data(List.of(), null, "UNKNOWN", false);
    if (input.visualType().equals("integer-list")
        && input.visualValue() instanceof Map<?, ?> map) {
      List<Object> values = map.get("values") instanceof List<?> list
          ? new ArrayList<>(list) : List.of();
      Integer length = map.get("length") instanceof Number number ? number.intValue() : null;
      return new Data(values, length, map.get("status") instanceof String status ? status : "UNKNOWN",
          Boolean.TRUE.equals(map.get("truncated")));
    }
    if (input.visualType().equals("array") && input.visualValue() instanceof List<?> raw) {
      List<Object> values = new ArrayList<>();
      Integer length = null;
      boolean truncated = false;
      for (Object item : raw) {
        if (item instanceof Map<?, ?> marker && Boolean.TRUE.equals(marker.get("truncated"))) {
          truncated = true;
          if (marker.get("length") instanceof Number number) length = number.intValue();
        } else values.add(item);
      }
      if (!truncated) length = values.size();
      return new Data(values, length, truncated ? "TRUNCATED" : "AVAILABLE", truncated);
    }
    return new Data(List.of(), null, "UNKNOWN", false);
  }

  private static boolean within(Node child, Node parent) {
    for (Node node = child; node != null; node = node.getParentNode().orElse(null))
      if (node == parent) return true;
    return false;
  }
}
