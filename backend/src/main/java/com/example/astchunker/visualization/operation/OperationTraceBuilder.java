package com.example.astchunker.visualization.operation;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.operation.*;
import com.example.astchunker.dto.OperationTraceResponse;
import com.example.astchunker.model.*;
import com.example.astchunker.model.operation.*;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Generic execution chronology, independent of any algorithm detector. */
@Component
public class OperationTraceBuilder {
  private record Pending(String id, String snapshot) {}

  private static final class Frame {
    final String id;
    final Map<String, String> scopes = new LinkedHashMap<>();
    final Map<String, OperationVariable> variables = new LinkedHashMap<>();
    final Map<String, Deque<Pending>> pending = new HashMap<>();
    final Map<String, String> declarationScopes = new HashMap<>();
    final Map<String, String> uncertainReferences = new HashMap<>();

    Frame(String id) {
      this.id = id;
    }
  }

  public OperationTraceResponse build(AstAnalyzer.Analysis analysis, OperationRun run) {
    return build(analysis, List.of(), run);
  }

  public OperationTraceResponse build(
      AstAnalyzer.Analysis analysis, List<AlgorithmHint> hints, OperationRun run) {
    Map<String, Node> nodes = new HashMap<>();
    analysis.compilationUnit().walk(n -> nodes.put(AstAnalyzer.nodeId(n), n));
    Map<String, AstVariable> declarations = new LinkedHashMap<>();
    analysis.variables().forEach(v -> declarations.put(v.astNodeId(), v));
    Map<String, Frame> frames = new HashMap<>();
    Map<String, OperationObject> objects = new LinkedHashMap<>();
    List<OperationStep> steps = new ArrayList<>();
    OperationTraceBudget budget = new OperationTraceBudget();
    String captureStatus = run.captureStatus(), terminationReason = run.terminationReason();
    List<String> warnings = new ArrayList<>(run.warnings());
    Map<String, Object> limits = new LinkedHashMap<>(run.limits());
    limits.put("maxSnapshotValues", OperationTraceBudget.MAX_VALUES);
    limits.put("maxSnapshotTextChars", OperationTraceBudget.MAX_TEXT_CHARS);
    long activation = 0;
    for (OperationCapture capture : run.captures()) {
      OperationSite site = capture.site();
      ExecutionContext context = capture.context();
      String frameId = "thread-" + context.threadId() + "/invocation-" + context.invocationId();
      Frame frame = frames.computeIfAbsent(frameId, Frame::new);
      List<OperationEffect> effects = new ArrayList<>();
      String exited = site.phase().equals("EXIT") ? site.attributes().get("scopeId") : null;
      if (site.kind().equals("LOOP"))
        effects.add(
            effect(
                "LOOP_" + site.phase(),
                site.operationId(),
                null,
                null,
                Map.of(),
                Map.of(
                    "loopId",
                    site.attributes().getOrDefault("loopId", site.source().astNodeId()))));
      for (String scope : site.scopePath())
        if (!scope.equals(exited) && !frame.scopes.containsKey(scope))
          frame.scopes.put(scope, frameId + "/scope-" + (++activation));
      if (site.kind().equals("SCOPE") && site.phase().equals("ENTER")) {
        String scope = site.attributes().get("scopeId");
        if (scope != null && !frame.scopes.containsKey(scope))
          frame.scopes.put(scope, frameId + "/scope-" + (++activation));
        effects.add(
            effect(
                "SCOPE_ENTER",
                frame.scopes.getOrDefault(scope, frameId),
                null,
                null,
                Map.of(),
                Map.of("scopeId", Objects.toString(scope, ""))));
      }
      boolean methodExit = site.kind().equals("METHOD_EXIT");
      if (exited != null || methodExit) {
        String instance = frame.scopes.get(exited);
        Iterator<OperationVariable> iterator = frame.variables.values().iterator();
        while (iterator.hasNext()) {
          OperationVariable old = iterator.next();
          if (methodExit || Objects.equals(instance, old.scopeInstanceId())) {
            effects.add(
                effect("VARIABLE_DESTROYED", old.instanceId(), old, null, Map.of(), target(old)));
            frame.uncertainReferences.remove(old.declarationId());
            iterator.remove();
          }
        }
        if (exited != null) {
          frame.scopes.remove(exited);
          effects.add(
              effect(
                  "SCOPE_EXIT",
                  Objects.toString(instance, frameId),
                  null,
                  null,
                  Map.of(),
                  Map.of("scopeId", exited)));
        }
      }
      Map<String, OperationVariable> beforeVariables = new LinkedHashMap<>(frame.variables);
      Map<String, OperationObject> beforeObjects = new LinkedHashMap<>(objects);
      Map<String, ObservationResult> values = new HashMap<>();
      Set<String> duplicates = new HashSet<>();
      for (ObservationResult value : capture.variables()) {
        if (values.putIfAbsent(value.astNodeId(), value) != null) duplicates.add(value.astNodeId());
      }
      duplicates.forEach(values::remove);
      Map<String, OperationObject> refreshedObjects = new LinkedHashMap<>();
      Set<String> uncertainObjects = new HashSet<>();
      boolean after = site.phase().equals("AFTER");
      for (String id : methodExit ? List.<String>of() : site.visibleDeclarationIds()) {
        AstVariable declaration = declarations.get(id);
        if (declaration == null) continue;
        if (id.equals(site.attributes().get("declarationId"))
            && site.attributes().containsKey("scopeId"))
          frame.declarationScopes.put(id, site.attributes().get("scopeId"));
        String staticScope = frame.declarationScopes.getOrDefault(id, scope(nodes.get(id)));
        if (exited != null && exited.equals(staticScope)) continue;
        String scopeInstance = frame.scopes.getOrDefault(staticScope, frameId);
        OperationVariable old = frame.variables.get(id);
        ObservationResult observed = values.get(id);
        boolean uninitialized =
            (old != null && old.valueStatus().equals("UNINITIALIZED"))
                || (after
                    && id.equals(site.attributes().get("declarationId"))
                    && "false".equals(site.attributes().get("hasInitializer")));
        String status =
            observed == null
                ? (uninitialized ? "UNINITIALIZED" : "UNAVAILABLE")
                : valueStatus(observed);
        String objectId =
            observed == null || observed.objectId() == null ? null : observed.objectId().toString();
        Object value = observed == null ? null : observed.visualValue();
        if (observed != null && objectId != null && "array".equals(observed.visualType())) {
          OperationObject object = array(observed, capture.sequence());
          objects.put(objectId.toString(), object);
          if (object.status().equals("AVAILABLE") || object.status().equals("TRUNCATED")) {
            OperationObject prior = refreshedObjects.get(objectId);
            if (prior == null || !prior.status().equals("AVAILABLE"))
              refreshedObjects.put(objectId, object);
          }
          value = Map.of("objectId", objectId);
          if (object.truncated()) status = "TRUNCATED";
          else if (!object.status().equals("AVAILABLE")) status = "UNAVAILABLE";
        }
        if (observed == null || status.equals("UNAVAILABLE")) {
          if (old != null && old.objectId() != null)
            frame.uncertainReferences.put(id, old.objectId());
          String uncertain = frame.uncertainReferences.get(id);
          if (uncertain != null) uncertainObjects.add(uncertain);
        } else frame.uncertainReferences.remove(id);
        OperationVariable current =
            new OperationVariable(
                scopeInstance + "/" + id,
                id,
                scopeInstance,
                declaration.name(),
                declaration.declaredType(),
                status,
                value,
                objectId);
        frame.variables.put(id, current);
        if (old == null) {
          OperationVariable reserved =
              new OperationVariable(
                  current.instanceId(),
                  id,
                  scopeInstance,
                  current.name(),
                  current.declaredType(),
                  "UNINITIALIZED",
                  null,
                  null);
          effects.add(
              effect(
                  "VARIABLE_CREATED",
                  current.instanceId(),
                  null,
                  reserved,
                  Map.of(),
                  target(current)));
          if (!status.equals("UNINITIALIZED")) {
            copyEffect(effects, current, site, nodes, beforeVariables, beforeObjects);
            effects.add(
                effect(
                    "VARIABLE_INITIALIZED",
                    current.instanceId(),
                    reserved,
                    current,
                    Map.of(),
                    target(current)));
          }
        } else if (after && !sameValue(old, current)) {
          copyEffect(effects, current, site, nodes, beforeVariables, beforeObjects);
          String change =
              old.valueStatus().equals("UNINITIALIZED")
                  ? "VARIABLE_INITIALIZED"
                  : !old.valueStatus().equals("AVAILABLE")
                          || !current.valueStatus().equals("AVAILABLE")
                      ? "VARIABLE_AVAILABILITY_CHANGED"
                      : "VARIABLE_WRITTEN";
          effects.add(
              effect(change, current.instanceId(), old, current, Map.of(), target(current)));
        }
      }
      objects.putAll(refreshedObjects);
      for (String id : uncertainObjects) {
        OperationObject old = objects.get(id);
        if (old != null && !refreshedObjects.containsKey(id))
          objects.put(
              id,
              new OperationObject(
                  old.objectId(),
                  old.type(),
                  old.values(),
                  old.length(),
                  "UNKNOWN",
                  old.truncated(),
                  old.observedAtSequence()));
      }
      if (after) {
        for (var entry : objects.entrySet()) {
          OperationObject old = beforeObjects.get(entry.getKey()), current = entry.getValue();
          if (old == null
              || !old.status().equals("AVAILABLE")
              || !current.status().equals("AVAILABLE")) continue;
          if (old.values().size() != current.values().size()) continue;
          for (int i = 0; i < current.values().size(); i++) {
            Object a = old.values().get(i), b = current.values().get(i);
            if (Objects.equals(a, b)) continue;
            boolean nested =
                a instanceof List<?>
                    || b instanceof List<?>
                    || a instanceof Map<?, ?>
                    || b instanceof Map<?, ?>;
            effects.add(
                effect(
                    nested ? "OBJECT_PROJECTION_CHANGED" : "ARRAY_ELEMENT_WRITTEN",
                    entry.getKey(),
                    a,
                    b,
                    nested
                        ? Map.of()
                        : writeSource(
                            site, nodes, beforeVariables, beforeObjects, current.objectId(), i, b),
                    Map.of(
                        "kind",
                        nested ? "OBJECT_PROJECTION" : "ARRAY_ELEMENT",
                        "objectId",
                        current.objectId(),
                        "index",
                        i)));
          }
        }
      }
      if (capture.conditionOutcome() != null)
        effects.add(
            effect(
                "CONDITION_EVALUATED",
                site.operationId(),
                null,
                capture.conditionOutcome(),
                Map.of(),
                Map.of("operationId", site.operationId())));
      if (after && site.kind().equals("RETURN"))
        capture.variables().stream()
            .filter(v -> v.astNodeId().startsWith("@return:"))
            .findFirst()
            .ifPresent(
                v ->
                    effects.add(
                        new OperationEffect(
                            "RETURN_VALUE",
                            site.operationId(),
                            null,
                            v.visualValue(),
                            Map.of(),
                            Map.of("kind", "RETURN"),
                            valueStatus(v))));
      String snapshot = "snapshot-" + capture.sequence();
      Deque<Pending> pending =
          frame.pending.computeIfAbsent(site.operationId(), key -> new ArrayDeque<>());
      Pending pair = null;
      String execution = "operation-" + capture.sequence();
      if (site.phase().equals("BEFORE")) {
        pair = new Pending(execution, snapshot);
        pending.push(pair);
      } else if (after && !pending.isEmpty()) {
        pair = pending.pop();
        execution = pair.id();
      }
      String status =
          site.phase().equals("BEFORE")
              ? "PENDING"
              : after && pair == null ? "OBSERVED_AFTER" : "COMPLETED";
      pruneObjects(frames, objects);
      if (!budget.include(
          frame.variables.values(),
          objects,
          effects,
          site.source().code(),
          site.attributes(),
          frame.scopes.values(),
          snapshot,
          execution,
          site.operationId())) {
        captureStatus = "TRUNCATED";
        terminationReason = "MAX_SNAPSHOT_" + budget.exceeded();
        warnings.add("Operation trace stopped: " + terminationReason);
        break;
      }
      steps.add(
          new OperationStep(
              capture.sequence(),
              execution,
              site.operationId(),
              site.phase(),
              site.kind(),
              site.source(),
              context,
              snapshot,
              pair == null ? null : pair.snapshot(),
              after ? snapshot : null,
              new ArrayList<>(frame.variables.values()),
              new LinkedHashMap<>(objects),
              effects,
              status,
              capture.conditionOutcome(),
              new ArrayList<>(frame.scopes.values()),
              site.attributes()));
      if (methodExit) frames.remove(frameId);
    }
    return new OperationTraceResponse(
        hints,
        steps,
        warnings,
        new OperationTraceInfo(
            2, "OPERATIONS", "SOURCE_OPERATION", captureStatus, terminationReason, limits));
  }

  private static void pruneObjects(
      Map<String, Frame> frames, Map<String, OperationObject> objects) {
    Set<String> live = new HashSet<>();
    for (Frame frame : frames.values()) {
      live.addAll(frame.uncertainReferences.values());
      for (OperationVariable variable : frame.variables.values()) {
        if (variable.objectId() != null) live.add(variable.objectId());
        references(variable.value(), live);
      }
    }
    // Follow any identity-bearing nested projections before pruning the cache.
    Deque<String> pending = new ArrayDeque<>(live);
    Set<String> visited = new HashSet<>();
    while (!pending.isEmpty()) {
      String id = pending.pop();
      if (!visited.add(id)) continue;
      OperationObject object = objects.get(id);
      if (object == null) continue;
      Set<String> nested = new HashSet<>();
      references(object.values(), nested);
      for (String child : nested) if (live.add(child)) pending.add(child);
    }
    objects.keySet().retainAll(live);
  }

  private static void references(Object value, Set<String> ids) {
    if (value instanceof Map<?, ?> map) {
      Object id = map.get("objectId");
      if (id != null) ids.add(id.toString());
      for (Object nested : map.values()) references(nested, ids);
    } else if (value instanceof Iterable<?> list) {
      for (Object nested : list) references(nested, ids);
    }
  }

  private static OperationObject array(ObservationResult value, long sequence) {
    List<Object> values = new ArrayList<>();
    Integer length = null;
    boolean truncated = false;
    if (value.visualValue() instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> marker && Boolean.TRUE.equals(marker.get("truncated"))) {
          truncated = true;
          if (marker.get("length") instanceof Number n) length = n.intValue();
        } else values.add(item);
      }
      if (!truncated) length = values.size();
    }
    return new OperationObject(
        value.objectId().toString(),
        value.declaredType(),
        values,
        length,
        !(value.visualValue() instanceof List<?>)
            ? "UNKNOWN"
            : truncated ? "TRUNCATED" : "AVAILABLE",
        truncated,
        sequence);
  }

  private static String valueStatus(ObservationResult value) {
    return "unavailable".equals(value.visualType()) ? "UNAVAILABLE" : "AVAILABLE";
  }

  private static String scope(Node node) {
    if (node instanceof Parameter) return "PARAMETERS";
    for (Node n = node == null ? null : node.getParentNode().orElse(null);
        n != null;
        n = n.getParentNode().orElse(null))
      if (n instanceof BlockStmt || n instanceof ForStmt || n instanceof ForEachStmt)
        return AstAnalyzer.nodeId(n);
    return "PARAMETERS";
  }

  private static boolean sameValue(OperationVariable a, OperationVariable b) {
    return a.valueStatus().equals(b.valueStatus())
        && Objects.equals(a.value(), b.value())
        && Objects.equals(a.objectId(), b.objectId());
  }

  private static Map<String, Object> target(OperationVariable value) {
    return Map.of(
        "kind",
        "VARIABLE",
        "variableInstanceId",
        value.instanceId(),
        "declarationId",
        value.declarationId());
  }

  private static OperationEffect effect(
      String type,
      String id,
      Object before,
      Object after,
      Map<String, Object> source,
      Map<String, Object> target) {
    return new OperationEffect(type, id, before, after, source, target, "CONFIRMED");
  }

  private static Map<String, Object> writeSource(
      OperationSite site,
      Map<String, Node> nodes,
      Map<String, OperationVariable> variables,
      Map<String, OperationObject> objects,
      String objectId,
      int index,
      Object after) {
    Node node = nodes.get(site.source().astNodeId());
    if (node instanceof ExpressionStmt statement) node = statement.getExpression();
    if (node instanceof AssignExpr assignment
        && assignment.getOperator() == AssignExpr.Operator.ASSIGN
        && assignment.getTarget() instanceof ArrayAccessExpr access) {
      Read destination = read(access, variables, objects),
          source = read(assignment.getValue(), variables, objects);
      if (destination != null
          && source != null
          && Objects.equals(destination.reference().get("objectId"), objectId)
          && Objects.equals(destination.reference().get("index"), index)
          && Objects.equals(source.value(), after)) return source.reference();
    }
    return Map.of();
  }

  private record Read(Object value, Map<String, Object> reference) {}

  private static void copyEffect(
      List<OperationEffect> effects,
      OperationVariable destination,
      OperationSite site,
      Map<String, Node> nodes,
      Map<String, OperationVariable> variables,
      Map<String, OperationObject> objects) {
    if (!destination.valueStatus().equals("AVAILABLE")) return;
    Node node = nodes.get(site.source().astNodeId());
    Expression expression = null;
    if (node instanceof VariableDeclarator declarator
        && destination.declarationId().equals(AstAnalyzer.nodeId(declarator)))
      expression = declarator.getInitializer().orElse(null);
    else if (node instanceof ExpressionStmt statement
        && statement.getExpression() instanceof AssignExpr assign
        && assign.getOperator() == AssignExpr.Operator.ASSIGN
        && assign.getTarget() instanceof NameExpr target
        && LocalVariableBindings.resolve(target)
            .filter(destination.declarationId()::equals)
            .isPresent()) expression = assign.getValue();
    Read read = read(expression, variables, objects);
    if (read != null && Objects.equals(read.value(), destination.value()))
      effects.add(
          effect(
              "VALUE_COPIED",
              destination.instanceId(),
              null,
              destination.value(),
              read.reference(),
              target(destination)));
  }

  private static Read read(
      Expression expression,
      Map<String, OperationVariable> variables,
      Map<String, OperationObject> objects) {
    while (expression instanceof EnclosedExpr enclosed) expression = enclosed.getInner();
    if (expression instanceof NameExpr name) {
      OperationVariable variable = variables.get(LocalVariableBindings.resolve(name).orElse(""));
      return variable != null && variable.valueStatus().equals("AVAILABLE")
          ? new Read(variable.value(), target(variable))
          : null;
    }
    if (expression instanceof ArrayAccessExpr access && access.getName() instanceof NameExpr name) {
      OperationVariable array = variables.get(LocalVariableBindings.resolve(name).orElse(""));
      if (array == null || !array.valueStatus().equals("AVAILABLE") || array.objectId() == null)
        return null;
      Integer index = null;
      if (access.getIndex() instanceof IntegerLiteralExpr literal) {
        try {
          index = literal.asNumber().intValue();
        } catch (NumberFormatException ignored) {
          return null;
        }
      } else if (access.getIndex() instanceof NameExpr pointer) {
        OperationVariable value = variables.get(LocalVariableBindings.resolve(pointer).orElse(""));
        if (value != null
            && value.valueStatus().equals("AVAILABLE")
            && value.value() instanceof Number number) index = number.intValue();
      }
      OperationObject object = objects.get(array.objectId().toString());
      if (index != null
          && object != null
          && object.status().equals("AVAILABLE")
          && index >= 0
          && index < object.values().size())
        return new Read(
            object.values().get(index),
            Map.of("kind", "ARRAY_ELEMENT", "objectId", array.objectId(), "index", index));
    }
    return null;
  }
}
