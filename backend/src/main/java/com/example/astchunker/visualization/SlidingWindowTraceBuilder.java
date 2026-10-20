package com.example.astchunker.visualization;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.ObservationResult;
import com.example.astchunker.model.SlidingWindowFrame;
import com.example.astchunker.model.SlidingWindowPattern;
import com.example.astchunker.model.VisualState;
import com.example.astchunker.model.VisualState.ArrayValue;
import com.example.astchunker.model.VisualState.IndexRange;
import com.example.astchunker.model.VisualState.Pointer;
import com.example.astchunker.model.VisualState.Scalar;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pairs pre-location window operations with later observations in the same context. */
final class SlidingWindowTraceBuilder {
  private record Binding(int index, AlgorithmHint hint, Node loop,
                         CallableDeclaration<?> method) {}
  private record Key(int hintIndex, long thread, long invocation, long input) {}
  private record Data(List<Object> values, Integer length, String status, boolean truncated) {}
  private record Snapshot(long sequence, Long right, Long left, Long accumulator) {}
  private record Pending(String operation, Snapshot before, Object value) {}
  private static final class Progress { Snapshot last; Pending pending; }

  private final List<Binding> bindings;
  private final Map<String, Node> nodes;
  private final Map<Key, Progress> progress = new HashMap<>();

  SlidingWindowTraceBuilder(List<AlgorithmHint> hints, Map<String, Node> nodes) {
    this.nodes = nodes;
    List<Binding> found = new ArrayList<>();
    for (int index = 0; index < hints.size(); index++) {
      AlgorithmHint hint = hints.get(index);
      if (!"sliding-window".equals(hint.type()) || hint.slidingWindow() == null) continue;
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
    for (Binding binding : bindings) {
      if (!within(point, binding.loop())
          || !binding.method().getNameAsString().equals(step.context().methodName())) continue;
      result.add(state(binding, step, point));
    }
    return List.copyOf(result);
  }

  private VisualState state(Binding binding, ExecutionObservation step, Node point) {
    AlgorithmHint hint = binding.hint();
    SlidingWindowPattern pattern = hint.slidingWindow();
    ObservationResult input = value(hint, step, "data");
    Data data = data(input);
    Long right = number(value(hint, step, "right"));
    Long left = number(value(hint, step, "left"));
    ObservationResult sizeObservation = value(hint, step, "windowSize");
    ObservationResult accumulatorObservation = value(hint, step, "accumulator");
    Long size = number(sizeObservation);
    Long accumulator = number(accumulatorObservation);
    boolean fixed = pattern.kind().equals("FIXED_SIZE");
    Long start = right == null ? null : fixed
        ? size == null ? null : Math.max(0, right - size + 1) : left;
    Long end = right == null ? null : right + 1;
    boolean rangeValid = start != null && end != null && data.length() != null
        && start >= 0 && start <= end && end <= data.length();
    boolean complete = input != null && input.objectId() != null
        && step.context().invocationId() != null && data.status().equals("AVAILABLE")
        && data.length() != null && right != null && accumulator != null && right >= 0
        && right < data.length() && rangeValid && (fixed ? size != null && size > 0
            : left != null && left <= right);
    Object entering = at(data, right);
    Long outgoingIndex = fixed ? right == null || size == null ? null : right - size : left;
    Object leaving = at(data, outgoingIndex);
    Long inputId = input == null ? null : input.objectId();
    Long invocation = step.context().invocationId();
    Progress history = complete ? progress.computeIfAbsent(new Key(binding.index(),
        step.context().threadId(), invocation, inputId), unused -> new Progress()) : null;
    Snapshot current = new Snapshot(step.sequence(), right, left, accumulator);
    String effect = data.truncated() ? "TRUNCATED" : "UNKNOWN";
    List<String> notes = new ArrayList<>();
    notes.add("RANGE_DOES_NOT_PROVE_ACCUMULATOR_MEMBERSHIP");
    if (!complete) notes.add("INCOMPLETE_WINDOW_SNAPSHOT");
    if (data.truncated()) notes.add("INPUT_TRUNCATED");
    if (history != null) {
      boolean skipped = history.last != null
          && (step.sequence() != history.last.sequence() + 1
              || right > history.last.right() + 1
              || !fixed && left > history.last.left() + 1);
      boolean backward = history.last != null
          && (right < history.last.right() || !fixed && left < history.last.left());
      if (backward) { effect = "VIOLATED"; history.pending = null; }
      else if (skipped) {
        effect = "UNKNOWN"; history.pending = null;
        notes.add("MISSING_INTERMEDIATE_SNAPSHOT");
      } else if (history.pending != null
          && history.pending.before().sequence() < step.sequence()) {
        String confirmed = confirm(history.pending, current);
        if (confirmed.equals("OBSERVED")) { effect = confirmed; history.pending = null; }
        else if (confirmed.equals("VIOLATED")) {
          effect = confirmed; history.pending = null;
        } else if (confirmed.equals("UNKNOWN")) {
          history.pending = null;
        }
      }
      String operation = operationAt(point, pattern);
      Object operationValue = "ADD".equals(operation) ? entering : leaving;
      if (operation != null && operationValue instanceof Number
          && !effect.equals("VIOLATED") && !skipped) {
        history.pending = new Pending(operation, current, operationValue);
        if (!effect.equals("OBSERVED")) effect = "PENDING";
      }
      history.last = current;
    } else if (inputId != null && invocation != null) {
      progress.remove(new Key(binding.index(), step.context().threadId(), invocation, inputId));
    }
    ArrayValue array = new ArrayValue(hint.variables().get("data"),
        pattern.declarationIds().get("data"), data.status(), data.values(),
        data.length(), data.truncated());
    Map<String, Pointer> pointers = new LinkedHashMap<>();
    pointers.put("right", pointer(hint, "right", right, data.length(), data.values().size()));
    if (!fixed) pointers.put("left", pointer(hint, "left", left,
        data.length(), data.values().size()));
    Map<String, Scalar> scalars = new LinkedHashMap<>();
    scalars.put("accumulator", scalar(hint, "accumulator", accumulatorObservation));
    if (fixed) scalars.put("windowSize", scalar(hint, "windowSize", sizeObservation));
    IndexRange range = new IndexRange(fixed ? "FIXED_SIZE_CANDIDATE" : "POINTER_SPAN",
        start, end, rangeValid ? start.equals(end) ? "EMPTY" : "VALID" : "UNKNOWN");
    SlidingWindowFrame frame = new SlidingWindowFrame(invocation, inputId, pattern.kind(),
        right, left, size, data.length(), start, end, entering, leaving,
        accumulator, effect, pattern.evidence(), step.snapshotPhase());
    return new VisualState("hint-" + binding.index(), binding.index(), hint.type(),
        hint.visualPlan(), complete ? "READY" : data.truncated() ? "TRUNCATED" : "PARTIAL",
        array, pointers, scalars, range, notes,
        null, null, null, null, null, null, frame);
  }

  private String operationAt(Node point, SlidingWindowPattern pattern) {
    Node add = nodes.get(pattern.operationAstNodeIds().get("add"));
    Node remove = nodes.get(pattern.operationAstNodeIds().get("remove"));
    if (add != null && remove != null && add.getBegin().isPresent()
        && remove.getBegin().isPresent() && point.getBegin().isPresent()
        && add.getBegin().get().line == remove.getBegin().get().line
        && add.getBegin().get().line == point.getBegin().get().line) return null;
    boolean atAdd = add != null && within(add, point);
    boolean atRemove = remove != null && within(remove, point);
    return atAdd == atRemove ? null : atAdd ? "ADD" : "REMOVE";
  }

  private static String confirm(Pending pending, Snapshot current) {
    if (!(pending.value() instanceof Number value)
        || pending.before().accumulator() == null || current.accumulator() == null)
      return "UNKNOWN";
    if (value.longValue() == 0) return "UNKNOWN";
    int before = pending.before().accumulator().intValue();
    int expected = pending.operation().equals("ADD")
        ? before + value.intValue() : before - value.intValue();
    if (current.accumulator().intValue() == expected) return "OBSERVED";
    return current.accumulator().intValue() == before ? "PENDING" : "VIOLATED";
  }

  private static boolean within(Node child, Node parent) {
    for (Node node = child; node != null; node = node.getParentNode().orElse(null))
      if (node == parent) return true;
    return false;
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

  private static Object at(Data data, Long index) {
    return index != null && index >= 0 && index < data.values().size()
        ? data.values().get(index.intValue()) : null;
  }

  private static Pointer pointer(AlgorithmHint hint, String role, Long index,
                                 Integer length, int capturedLength) {
    String status = index == null ? "MISSING" : length == null ? "ARRAY_UNAVAILABLE"
        : index < 0 || index >= length ? "OUT_OF_BOUNDS"
        : index >= capturedLength ? "NOT_CAPTURED" : "VALID";
    return new Pointer(hint.variables().get(role), hint.variableDeclarationIds().get(role),
        index, status);
  }

  private static Scalar scalar(AlgorithmHint hint, String role, ObservationResult observation) {
    Object value = observation == null ? null : observation.visualValue();
    return new Scalar(hint.variables().get(role), hint.variableDeclarationIds().get(role),
        value, number(observation) == null ? "MISSING" : "AVAILABLE");
  }
}
