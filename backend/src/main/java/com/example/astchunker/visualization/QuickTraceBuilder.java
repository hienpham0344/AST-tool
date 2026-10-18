package com.example.astchunker.visualization;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionContext;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.ObservationResult;
import com.example.astchunker.model.QuickFrame;
import com.example.astchunker.model.SortFrame.Comparison;
import com.example.astchunker.model.SortFrame.Mutation;
import com.example.astchunker.model.SortPattern.Direction;
import com.example.astchunker.model.VisualState;
import com.example.astchunker.model.VisualState.ArrayValue;
import com.example.astchunker.model.VisualState.IndexRange;
import com.example.astchunker.model.VisualState.Pointer;
import com.example.astchunker.model.VisualState.Scalar;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects proven Quick operations onto the current JDI snapshot. */
final class QuickTraceBuilder {
  private record Binding(int index, AlgorithmHint hint, MethodDeclaration method,
      String comparisonStatementId, String innerTempName, String finalTempName) {}

  private final List<Binding> bindings;
  private final Map<Long, Long> mostRecentRootByThread = new HashMap<>();

  QuickTraceBuilder(List<AlgorithmHint> hints, Map<String, Node> nodes) {
    List<Binding> found = new ArrayList<>();
    for (int index = 0; index < hints.size(); index++) {
      AlgorithmHint hint = hints.get(index);
      if (hint.quick() == null || !hint.type().equals("quick-sort")) continue;
      Node owner = nodes.get(hint.methodAstNodeId());
      Node comparison = nodes.get(hint.quick().operationAstNodeIds().get("comparison"));
      if (!(owner instanceof MethodDeclaration method) || comparison == null) continue;
      Node branch = comparison.findAncestor(com.github.javaparser.ast.stmt.IfStmt.class).orElse(null);
      if (branch == null) continue;
      found.add(new Binding(index, hint, method, AstAnalyzer.nodeId(branch),
          tempName(nodes.get(hint.quick().operationAstNodeIds().get("innerSwapSave"))),
          tempName(nodes.get(hint.quick().operationAstNodeIds().get("finalSwapSave")))));
    }
    bindings = List.copyOf(found);
  }

  List<VisualState> states(ExecutionObservation step, ExecutionObservation previous, Node point,
      String mainClass) {
    if (step.context() == null || point == null) return List.of();
    List<VisualState> result = new ArrayList<>();
    for (Binding binding : bindings) {
      if (!step.context().className().equals(mainClass)) continue;
      if (point.findAncestor(MethodDeclaration.class).orElse(null) == binding.method()
          && step.context().methodName().equals(binding.method().getNameAsString())) {
        result.add(inMethod(binding, step, previous));
      } else {
        NameExpr arrayArgument = completedCallArray(point, binding.method());
        if (arrayArgument != null) result.add(completedArray(binding, step, arrayArgument));
      }
    }
    return List.copyOf(result);
  }

  private VisualState inMethod(Binding binding, ExecutionObservation step,
      ExecutionObservation previous) {
    AlgorithmHint hint = binding.hint();
    Map<String, String> operations = hint.quick().operationAstNodeIds();
    ArrayValue array = array(value(hint, step, "array"), hint.variables().get("array"),
        hint.variableDeclarationIds().get("array"));
    Integer low = integer(value(hint, step, "low"));
    Integer high = integer(value(hint, step, "high"));
    Integer scan = integer(value(hint, step, "scan"));
    Integer store = integer(value(hint, step, "store"));
    ObservationResult pivotVariable = value(hint, step, "pivot");
    Object pivot = available(pivotVariable) ? pivotVariable.visualValue() : null;
    if (low != null && high != null && array.length() != null && low == 0
        && high == array.length() - 1 && step.context().invocationId() != null)
      mostRecentRootByThread.put(step.context().threadId(), step.context().invocationId());

    String pointId = step.statementAstNodeId();
    Comparison comparison = null;
    if (pointId.equals(binding.comparisonStatementId()) && inArray(array, scan)
        && pivot instanceof Number pivotNumber && array.values().get(scan) instanceof Number current) {
      BinaryExpr operator = (BinaryExpr) findComparisonNode(binding, operations);
      boolean placeLeft = switch (operator.getOperator()) {
        case LESS -> current.intValue() < pivotNumber.intValue();
        case LESS_EQUALS -> current.intValue() <= pivotNumber.intValue();
        case GREATER -> current.intValue() > pivotNumber.intValue();
        case GREATER_EQUALS -> current.intValue() >= pivotNumber.intValue();
        default -> false;
      };
      comparison = new Comparison(scan, high, current, pivot, placeLeft,
          "AVAILABLE_FROM_SNAPSHOT", "PARTITION_LEFT", placeLeft);
    }
    Mutation pending = pending(binding, step, array, store, scan, high);
    Mutation completed = completed(binding, step, previous, array, store, scan, high);
    String partitionStatus = "UNKNOWN";
    if (completed != null && completed.kind().equals("FINAL_PIVOT_SWAP_COMPLETED"))
      partitionStatus = partitionStatus(array, low, high, store, pivot,
          hint.quick().direction());
    String phase = comparison != null ? "PARTITION_COMPARE"
        : pending != null ? "SWAP_PENDING"
        : completed != null ? "SWAP_COMPLETED"
        : pointId.equals(operations.get("pivotRead")) ? "PIVOT_READ_PENDING"
        : "PARTITION_PROGRESS";
    QuickFrame frame = new QuickFrame(phase, step.context().invocationId(), low, high,
        high, pivot, scan, store, comparison, pending, completed, partitionStatus,
        "UNKNOWN", "BEFORE_LOCATION");
    Map<String, Pointer> pointers = new LinkedHashMap<>();
    pointers.put("low", pointer(hint, "low", low, array));
    pointers.put("high", pointer(hint, "high", high, array));
    pointers.put("scan", pointer(hint, "scan", scan, array));
    pointers.put("store", pointer(hint, "store", store, array));
    Map<String, Scalar> scalars = Map.of("pivot", new Scalar(hint.variables().get("pivot"),
        hint.variableDeclarationIds().get("pivot"), pivot,
        pivot == null ? "MISSING" : "AVAILABLE"));
    IndexRange range = new IndexRange("ACTIVE_PARTITION",
        low == null ? null : low.longValue(), high == null ? null : high.longValue() + 1,
        low == null || high == null ? "UNKNOWN" : low > high ? "EMPTY" : "VALID");
    return new VisualState("hint-" + binding.index(), binding.index(), "quick-sort",
        hint.visualPlan(), array.status().equals("AVAILABLE") ? "READY" : "UNAVAILABLE",
        array, pointers, scalars, range, List.of(), null, frame);
  }

  private VisualState completedArray(Binding binding, ExecutionObservation step,
      NameExpr arrayArgument) {
    String declarationId = LocalVariableBindings.resolve(arrayArgument).orElse(null);
    ObservationResult observed = step.variables().stream()
        .filter(variable -> Objects.equals(variable.astNodeId(), declarationId)
            && variable.variableName().equals(arrayArgument.getNameAsString()))
        .findFirst().orElse(null);
    ArrayValue array = array(observed, arrayArgument.getNameAsString(), declarationId);
    String status = sorted(array, binding.hint().quick().direction())
        ? "VERIFIED_SORTED" : array.status().equals("AVAILABLE") && !array.truncated()
            ? "NOT_SORTED" : "UNKNOWN";
    QuickFrame frame = new QuickFrame("FULL_ARRAY_OBSERVED",
        mostRecentRootByThread.get(step.context().threadId()), 0,
        array.length() == null ? null : array.length() - 1, null, null, null, null,
        null, null, null, "UNKNOWN", status, "BEFORE_LOCATION");
    return new VisualState("hint-" + binding.index(), binding.index(), "quick-sort",
        binding.hint().visualPlan(), array.status().equals("AVAILABLE") ? "READY" : "UNAVAILABLE",
        array, Map.of(), Map.of(), new IndexRange("FULL_ARRAY", 0L,
            array.length() == null ? null : array.length().longValue(),
            array.length() == null ? "UNKNOWN" : "VALID"), List.of(), null, frame);
  }

  private Mutation pending(Binding binding, ExecutionObservation step, ArrayValue array,
      Integer store, Integer scan, Integer high) {
    Map<String, String> ids = binding.hint().quick().operationAstNodeIds();
    String point = step.statementAstNodeId();
    boolean inner = point.equals(ids.get("innerSwapSave"))
        || point.equals(ids.get("innerSwapLeftWrite"))
        || point.equals(ids.get("innerSwapRightWrite"));
    boolean end = point.equals(ids.get("finalSwapSave"))
        || point.equals(ids.get("finalSwapLeftWrite"))
        || point.equals(ids.get("finalSwapRightWrite"));
    Integer right = inner ? scan : end ? high : null;
    if (!inArray(array, store) || !inArray(array, right)) return null;
    if (point.equals(ids.get("innerSwapSave")) || point.equals(ids.get("finalSwapSave")))
      return new Mutation("SAVE_LEFT_VALUE", store, right, array.values().get(store),
          "PENDING_BEFORE_LOCATION");
    if (point.equals(ids.get("innerSwapLeftWrite")) || point.equals(ids.get("finalSwapLeftWrite")))
      return new Mutation("WRITE_RIGHT_TO_LEFT", right, store, array.values().get(right),
          "PENDING_BEFORE_LOCATION");
    String name = inner ? binding.innerTempName() : binding.finalTempName();
    ObservationResult saved = step.variables().stream()
        .filter(variable -> variable.variableName().equals(name)).findFirst().orElse(null);
    return new Mutation("WRITE_SAVED_TO_RIGHT", store, right,
        saved == null ? null : saved.visualValue(),
        saved == null ? "VALUE_UNAVAILABLE" : "PENDING_BEFORE_LOCATION");
  }

  private Mutation completed(Binding binding, ExecutionObservation step,
      ExecutionObservation previous, ArrayValue current, Integer store,
      Integer scan, Integer high) {
    if (previous == null || previous.context() == null || step.context() == null
        || !Objects.equals(previous.context().invocationId(), step.context().invocationId())
        || previous.context().threadId() != step.context().threadId()
        || previous.sequence() + 1 != step.sequence()) return null;
    Map<String, String> ids = binding.hint().quick().operationAstNodeIds();
    boolean inner = previous.statementAstNodeId().equals(ids.get("innerSwapRightWrite"));
    boolean end = previous.statementAstNodeId().equals(ids.get("finalSwapRightWrite"));
    if (!inner && !end) return null;
    Integer right = inner ? scan : high;
    ArrayValue before = array(value(binding.hint(), previous, "array"),
        binding.hint().variables().get("array"),
        binding.hint().variableDeclarationIds().get("array"));
    if (!inArray(before, store) || !inArray(before, right)
        || !inArray(current, store) || !inArray(current, right)
        || before.truncated() || current.truncated()) return null;
    String savedName = inner ? binding.innerTempName() : binding.finalTempName();
    ObservationResult saved = previous.variables().stream()
        .filter(variable -> variable.variableName().equals(savedName)).findFirst().orElse(null);
    if (saved == null || !Objects.equals(before.values().get(store), current.values().get(store))
        || !Objects.equals(current.values().get(right), saved.visualValue())) return null;
    return new Mutation(end ? "FINAL_PIVOT_SWAP_COMPLETED" : "INNER_SWAP_COMPLETED",
        store, right, saved.visualValue(), "OBSERVED_AFTER_WRITE");
  }

  private String partitionStatus(ArrayValue array, Integer low, Integer high,
      Integer store, Object pivot, Direction direction) {
    if (low == null || high == null || store == null || !(pivot instanceof Number)
        || array.truncated() || !inArray(array, store) || low < 0
        || high >= array.values().size() || store < low || store > high
        || !Objects.equals(array.values().get(store), pivot)) return "UNKNOWN";
    int pivotValue = ((Number) pivot).intValue();
    for (int index = low; index <= high; index++) {
      if (index == store) continue;
      if (!(array.values().get(index) instanceof Number number)) return "UNKNOWN";
      boolean valid = index < store
          ? direction == Direction.ASCENDING ? number.intValue() <= pivotValue
              : number.intValue() >= pivotValue
          : direction == Direction.ASCENDING ? number.intValue() >= pivotValue
              : number.intValue() <= pivotValue;
      if (!valid) return "NOT_PARTITIONED";
    }
    return "VERIFIED_PARTITION";
  }

  private boolean sorted(ArrayValue array, Direction direction) {
    if (!array.status().equals("AVAILABLE") || array.truncated()) return false;
    for (int index = 1; index < array.values().size(); index++) {
      if (!(array.values().get(index - 1) instanceof Number first)
          || !(array.values().get(index) instanceof Number second)) return false;
      if (direction == Direction.ASCENDING ? first.intValue() > second.intValue()
          : first.intValue() < second.intValue()) return false;
    }
    return true;
  }

  private ObservationResult value(AlgorithmHint hint, ExecutionObservation step, String role) {
    String id = hint.variableDeclarationIds().get(role);
    String name = hint.variables().get(role);
    return step.variables().stream()
        .filter(variable -> Objects.equals(variable.astNodeId(), id)
            && variable.variableName().equals(name)).findFirst().orElse(null);
  }

  private static Integer integer(ObservationResult variable) {
    return variable != null && variable.visualValue() instanceof Number number
        ? number.intValue() : null;
  }

  private static boolean available(ObservationResult variable) {
    return variable != null && !variable.visualType().equals("unavailable")
        && !variable.visualType().equals("null");
  }

  private static ArrayValue array(ObservationResult variable, String name, String id) {
    if (!available(variable) || !variable.visualType().equals("array")
        || !(variable.visualValue() instanceof List<?> observed))
      return new ArrayValue(name, id, "UNAVAILABLE", List.of(), null, false);
    List<Object> values = new ArrayList<>();
    Integer length = null;
    boolean truncated = false;
    for (Object element : observed) {
      if (element instanceof Map<?, ?> marker && Boolean.TRUE.equals(marker.get("truncated"))
          && marker.get("length") instanceof Integer total) {
        length = total;
        truncated = true;
      } else if (element == null || element instanceof Number || element instanceof String
          || element instanceof Boolean) values.add(element);
      else return new ArrayValue(name, id, "UNSUPPORTED", List.of(), null, false);
    }
    if (!truncated) length = values.size();
    return new ArrayValue(name, id, "AVAILABLE", values, length, truncated);
  }

  private static boolean inArray(ArrayValue array, Integer index) {
    return array.status().equals("AVAILABLE") && index != null && index >= 0
        && index < array.values().size();
  }

  private static Pointer pointer(AlgorithmHint hint, String role, Integer index,
      ArrayValue array) {
    return new Pointer(hint.variables().get(role), hint.variableDeclarationIds().get(role),
        index == null ? null : index.longValue(), index == null ? "MISSING"
            : inArray(array, index) ? "VALID" : "OUT_OF_BOUNDS");
  }

  private Node findComparisonNode(Binding binding, Map<String, String> operations) {
    return binding.method().findAll(BinaryExpr.class).stream()
        .filter(node -> AstAnalyzer.nodeId(node).equals(operations.get("comparison")))
        .findFirst().orElseThrow();
  }

  private static String tempName(Node save) {
    if (save == null) return "";
    return save.findFirst(VariableDeclarator.class).map(VariableDeclarator::getNameAsString)
        .orElse("");
  }

  private static NameExpr completedCallArray(Node point, MethodDeclaration quick) {
    if (!(point instanceof Statement current)
        || !(current.getParentNode().orElse(null) instanceof BlockStmt block)) return null;
    int index = block.getStatements().indexOf(current);
    if (index <= 0 || !(block.getStatement(index - 1) instanceof ExpressionStmt previous)
        || !(previous.getExpression() instanceof MethodCallExpr call)
        || !call.getNameAsString().equals(quick.getNameAsString())
        || call.getArguments().size() != 3
        || !(call.getArgument(0) instanceof NameExpr array)
        || !call.getArgument(1).toString().equals("0")
        || !call.getArgument(2).toString().equals(array.getNameAsString() + ".length - 1"))
      return null;
    return array;
  }
}
