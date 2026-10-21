package com.example.astchunker.visualization;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.ObservationResult;
import com.example.astchunker.model.PrefixSumFrame;
import com.example.astchunker.model.PrefixSumPattern;
import com.example.astchunker.model.VisualState;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stateless projection: no array values or progress are carried across observations/invocations.
 */
final class PrefixSumTraceBuilder {
  private record Binding(
      int index,
      AlgorithmHint hint,
      Node loop,
      MethodDeclaration method,
      Node allocation,
      Node seed,
      Node recurrence) {}

  private final List<Binding> bindings;
  private final Map<String, Node> nodes;

  PrefixSumTraceBuilder(List<AlgorithmHint> hints, Map<String, Node> nodes) {
    this.nodes = nodes;
    List<Binding> found = new ArrayList<>();
    for (int i = 0; i < hints.size(); i++) {
      var h = hints.get(i);
      if (!"prefix-sum".equals(h.type()) || h.prefixSum() == null) continue;
      var ops = h.prefixSum().operationAstNodeIds();
      Node loop = nodes.get(ops.get("loop"));
      Node allocation = nodes.get(ops.get("allocation"));
      Node seed = nodes.get(ops.get("seed"));
      Node recurrence = nodes.get(ops.get("recurrence"));
      if (loop != null
          && allocation != null
          && seed != null
          && recurrence != null
          && nodes.get(h.methodAstNodeId()) instanceof MethodDeclaration method
          && LocalVariableBindings.callable(loop) == method)
        found.add(new Binding(i, h, loop, method, allocation, seed, recurrence));
    }
    bindings = List.copyOf(found);
  }

  List<VisualState> states(ExecutionObservation step, Node point, String mainClass) {
    if (point == null || step.context() == null || !mainClass.equals(step.context().className()))
      return List.of();
    List<VisualState> result = new ArrayList<>();
    for (var b : bindings) {
      if (LocalVariableBindings.callable(point) != b.method()
          || !b.method().getNameAsString().equals(step.context().methodName())) continue;
      Node direct = direct(point, (BlockStmt) b.loop().getParentNode().orElseThrow());
      boolean active =
          within(point, b.loop())
              || intersects(point, b.allocation())
              || intersects(point, b.seed())
              || direct != null && after(direct, b.loop());
      if (active) result.add(state(b, step, point));
    }
    return List.copyOf(result);
  }

  private VisualState state(Binding b, ExecutionObservation step, Node point) {
    var h = b.hint();
    ObservationResult sourceValue = role(b, step, "data");
    ObservationResult prefixValue = role(b, step, "prefix");
    var source = array(h, "data", sourceValue);
    var prefix = array(h, "prefix", prefixValue);
    Long sourceId = identity(sourceValue), prefixId = identity(prefixValue);
    boolean sentinel = h.prefixSum().representation().equals("ZERO_SENTINEL");
    boolean trusted =
        step.context().invocationId() != null
            && sourceId != null
            && prefixId != null
            && !sourceId.equals(prefixId)
            && source.length() != null
            && prefix.length() != null
            && !source.status().equals("UNKNOWN")
            && !prefix.status().equals("UNKNOWN")
            && prefix.length().longValue() == source.length().longValue() + (sentinel ? 1 : 0);
    boolean inside = within(point, b.loop());
    Long index = inside ? integer(role(b, step, "index")) : null;
    Long target = index == null ? null : index + (sentinel ? 1 : 0);
    Long previous = target == null ? null : target - 1;
    Object sourceCell = cell(source, index),
        previousCell = cell(prefix, previous),
        targetCell = cell(prefix, target);
    boolean atRecurrence =
        intersects(point, b.recurrence())
            && step.lineNumber() >= b.recurrence().getBegin().orElseThrow().line
            && step.lineNumber() <= endLine(b.recurrence());
    String recurrenceStatus =
        !atRecurrence
            ? "NOT_AT_RECURRENCE"
            : !trusted || index == null
                ? "UNKNOWN"
                : !bounded(source, index) || !bounded(prefix, previous) || !bounded(prefix, target)
                    ? "OUT_OF_BOUNDS"
                    : sourceCell == null || previousCell == null || targetCell == null
                        ? "NOT_CAPTURED"
                        : "PENDING";

    Long end = null;
    if (trusted) {
      if (inside
          && index != null
          && index >= (sentinel ? 0 : 1)
          && index <= source.length()
          && (index < source.length() || !atRecurrence)) {
        end = index + (sentinel ? 1 : 0);
        // A later source line in the same proven unconditional loop follows its write.
        if (index < source.length()
            && step.lineNumber() > endLine(b.recurrence())
            && after(point, b.recurrence())) end++;
        if (end > prefix.length()) end = null;
      } else if (!inside && after(point, b.loop()) && step.lineNumber() > endLine(b.loop())) {
        end = prefix.length().longValue();
      } else if (!inside && step.lineNumber() > endLine(b.seed()) && after(point, b.seed())) {
        end = Math.min(1L, prefix.length());
      } else if (intersects(point, b.seed()) || intersects(point, b.allocation())) {
        end = 0L;
      }
    }
    List<PrefixSumFrame.ConfirmedCell> cells = new ArrayList<>();
    if (end != null) {
      for (int i = 0; i < Math.min(end, prefix.values().size()); i++) {
        Object value = cell(prefix, (long) i);
        if (value != null) cells.add(new PrefixSumFrame.ConfirmedCell(i, value, "CONFIRMED"));
      }
    }
    String progress = end == null ? "UNKNOWN" : cells.size() < end ? "TRUNCATED" : "CONFIRMED";
    String seed =
        end != null && end > 0 ? "CONFIRMED" : intersects(point, b.seed()) ? "PENDING" : "UNKNOWN";
    PrefixSumFrame.Query query = query(b, step, point, source, prefix, trusted);
    PrefixSumFrame frame =
        new PrefixSumFrame(
            1,
            h.prefixSum().representation(),
            step.context().invocationId(),
            sourceId,
            prefixId,
            source,
            prefix,
            index,
            index,
            previous,
            target,
            sourceCell,
            previousCell,
            targetCell,
            recurrenceStatus,
            seed,
            end,
            cells,
            progress,
            query,
            step.snapshotPhase());
    String status =
        !trusted || end == null
            ? "PARTIAL"
            : source.truncated() || prefix.truncated() ? "TRUNCATED" : "READY";
    return new VisualState(
        "hint-" + b.index(),
        b.index(),
        h.type(),
        h.visualPlan(),
        status,
        source,
        Map.of(),
        Map.of(),
        null,
        List.of(
            "CURRENT_RECURRENCE_IS_PENDING_BEFORE_LOCATION",
            "DEFAULT_ZERO_DOES_NOT_PROVE_CONSTRUCTION"),
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

  private PrefixSumFrame.Query query(
      Binding b,
      ExecutionObservation step,
      Node point,
      VisualState.ArrayValue source,
      VisualState.ArrayValue prefix,
      boolean trusted) {
    for (PrefixSumPattern.Query q : b.hint().prefixSum().queries()) {
      Node expression = nodes.get(q.expressionAstNodeId());
      if (expression == null) continue;
      // Sibling initializers can change endpoints or overwrite the result within this source line.
      if (expression
          .findAncestor(VariableDeclarationExpr.class)
          .map(declaration -> declaration.getVariables().size() > 1)
          .orElse(false)) continue;
      boolean pending = intersects(point, expression);
      Node statement = expression.findAncestor(Statement.class).orElse(null);
      Node next = nextStatement(statement);
      boolean observed =
          next != null
              && intersects(point, next)
              && step.lineNumber() > endLine(statement)
              && step.lineNumber() == next.getBegin().orElseThrow().line;
      String resultDeclaration = queryResult(expression);
      if (observed
          && (Objects.equals(resultDeclaration, q.leftDeclarationId())
              || Objects.equals(resultDeclaration, q.rightDeclarationId()))) {
        // An endpoint overwritten by the query no longer describes the expression's inputs.
        return new PrefixSumFrame.Query(
            q.expressionAstNodeId(), null, null, null, null, null, null, null, "UNKNOWN");
      }
      if (!pending && !observed) continue;
      Long left =
          integer(value(step, q.leftDeclarationId(), endpointName(q.leftExpressionAstNodeId())));
      Long right =
          integer(value(step, q.rightDeclarationId(), endpointName(q.rightExpressionAstNodeId())));
      Long upper = right == null ? null : right + 1;
      String status =
          !trusted || left == null || right == null
              ? "UNKNOWN"
              : left < 0
                      || right < left
                      || right >= source.length()
                      || !bounded(prefix, upper)
                      || !bounded(prefix, left)
                  ? "OUT_OF_BOUNDS"
                  : cell(prefix, upper) == null || cell(prefix, left) == null
                      ? "NOT_CAPTURED"
                      : "PENDING";
      Object result = null;
      if (observed && status.equals("PENDING")) {
        String declaration = resultDeclaration;
        ObservationResult scalar = declaration == null ? null : value(step, declaration, null);
        if (scalar != null && integerObject(scalar.visualValue()) != null) {
          result = scalar.visualValue();
          status = "OBSERVED";
        } else status = "UNKNOWN";
      }
      return new PrefixSumFrame.Query(
          q.expressionAstNodeId(),
          left,
          right,
          upper,
          left,
          cell(prefix, upper),
          cell(prefix, left),
          result,
          status);
    }
    return null;
  }

  private String endpointName(String id) {
    return nodes.get(id) instanceof NameExpr name ? name.getNameAsString() : null;
  }

  private static String queryResult(Node expression) {
    Node current = expression;
    while (current.getParentNode().isPresent()) {
      Node parent = current.getParentNode().orElseThrow();
      if (parent instanceof VariableDeclarator declaration
          && declaration.getInitializer().orElse(null) == current)
        return com.example.astchunker.ast.AstAnalyzer.nodeId(declaration);
      if (parent instanceof AssignExpr assignment
          && assignment.getOperator() == AssignExpr.Operator.ASSIGN
          && assignment.getValue() == current
          && assignment.getTarget() instanceof NameExpr name)
        return LocalVariableBindings.resolve(name).orElse(null);
      if (parent instanceof Statement) break;
      current = parent;
    }
    return null;
  }

  private static Node nextStatement(Node statement) {
    if (statement == null || !(statement.getParentNode().orElse(null) instanceof BlockStmt block))
      return null;
    int i = block.getStatements().indexOf(statement);
    return i < 0 || i + 1 >= block.getStatements().size() ? null : block.getStatement(i + 1);
  }

  private static ObservationResult role(Binding b, ExecutionObservation step, String role) {
    return value(
        step, b.hint().prefixSum().declarationIds().get(role), b.hint().variables().get(role));
  }

  private static ObservationResult value(ExecutionObservation step, String id, String name) {
    if (id == null) return null;
    var matches =
        step.variables().stream()
            .filter(
                v -> id.equals(v.astNodeId()) && (name == null || name.equals(v.variableName())))
            .toList();
    return matches.size() == 1 ? matches.get(0) : null;
  }

  private static VisualState.ArrayValue array(
      AlgorithmHint hint, String role, ObservationResult value) {
    List<Object> values = new ArrayList<>();
    Integer length = null;
    boolean truncated = false;
    String status = "UNKNOWN";
    if (value != null
        && "array".equals(value.visualType())
        && value.visualValue() instanceof List<?> raw) {
      boolean valid = true;
      for (Object item : raw) {
        if (item instanceof Map<?, ?> marker && Boolean.TRUE.equals(marker.get("truncated"))) {
          truncated = true;
          if (marker.get("length") instanceof Number n
              && n.longValue() >= 0
              && n.longValue() <= Integer.MAX_VALUE) length = n.intValue();
        } else {
          values.add(item);
          valid &= integerObject(item) != null;
        }
      }
      if (!truncated) length = values.size();
      status =
          valid && length != null && length >= values.size()
              ? truncated ? "TRUNCATED" : "AVAILABLE"
              : "UNKNOWN";
    }
    return new VisualState.ArrayValue(
        hint.variables().get(role),
        hint.prefixSum().declarationIds().get(role),
        status,
        values,
        length,
        truncated);
  }

  private static Long identity(ObservationResult value) {
    return value == null ? null : value.objectId();
  }

  private static Long integer(ObservationResult value) {
    Object number = value == null ? null : integerObject(value.visualValue());
    return number instanceof Number n ? n.longValue() : null;
  }

  private static Object integerObject(Object value) {
    return value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long
        ? value
        : null;
  }

  private static boolean bounded(VisualState.ArrayValue array, Long index) {
    return array.length() != null && index != null && index >= 0 && index < array.length();
  }

  private static Object cell(VisualState.ArrayValue array, Long index) {
    return bounded(array, index) && index < array.values().size()
        ? integerObject(array.values().get(index.intValue()))
        : null;
  }

  private static int endLine(Node node) {
    return node.getEnd().orElseThrow().line;
  }

  private static boolean after(Node first, Node second) {
    return first.getBegin().isPresent()
        && second.getEnd().isPresent()
        && first.getBegin().orElseThrow().isAfter(second.getEnd().orElseThrow());
  }

  private static boolean intersects(Node first, Node second) {
    return within(first, second) || within(second, first);
  }

  private static boolean within(Node child, Node parent) {
    for (Node current = child; current != null; current = current.getParentNode().orElse(null))
      if (current == parent) return true;
    return false;
  }

  private static Node direct(Node child, BlockStmt block) {
    for (Node current = child; current != null; current = current.getParentNode().orElse(null))
      if (current.getParentNode().orElse(null) == block) return current;
    return null;
  }
}
