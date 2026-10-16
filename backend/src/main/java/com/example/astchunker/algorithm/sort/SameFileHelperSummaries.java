package com.example.astchunker.algorithm.sort;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.stmt.*;
import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Bounded summaries of source-defined helpers; unresolved effects never prove a sort. */
final class SameFileHelperSummaries {
  static final int MAX_CALL_DEPTH = 2;
  static final int MAX_VISITED_STATEMENTS = 32;
  private final CompilationUnit unit;
  private final SortSemanticFacts.Analysis facts;

  private SameFileHelperSummaries(CompilationUnit unit, SortSemanticFacts.Analysis facts) {
    this.unit = java.util.Objects.requireNonNull(unit);
    this.facts = java.util.Objects.requireNonNull(facts);
  }

  static SameFileHelperSummaries analyze(CompilationUnit unit, SortSemanticFacts.Analysis facts) {
    return new SameFileHelperSummaries(unit, facts);
  }

  Resolution<Exchange> resolveExchange(MethodCallExpr call) {
    Resolution<BodySummary> result = resolve(call, Map.of(), new LinkedHashSet<>(), 0, false);
    if (result.value().isEmpty()) return failure(result.status());
    BodySummary body = result.value().get();
    return resolved(new Exchange(AstAnalyzer.nodeId(call), body.callables().get(0), body.left().sequence().orElseThrow(),
        body.left().index().orElseThrow(), body.right().index().orElseThrow(), body.statements(), body.callables()));
  }

  Resolution<Comparison> resolveComparison(MethodCallExpr call) {
    Resolution<BodySummary> result = resolve(call, Map.of(), new LinkedHashSet<>(), 0, true);
    if (result.value().isEmpty()) return failure(result.status());
    BodySummary body = result.value().get();
    return resolved(new Comparison(AstAnalyzer.nodeId(call), body.callables().get(0), body.left(), BinaryExpr.Operator.GREATER,
        body.right(), body.statements(), body.callables()));
  }

  private Resolution<BodySummary> resolve(MethodCallExpr call, Map<String, Operand> outer,
      Set<String> visited, int statementCount, boolean comparison) {
    if (call.findCompilationUnit().orElse(null) != unit) return failure(Status.EXTERNAL_OR_DYNAMIC);
    TypeDeclaration<?> owner = call.findAncestor(TypeDeclaration.class).orElse(null);
    if (owner == null) return failure(Status.EXTERNAL_OR_DYNAMIC);
    // A name-shaped qualifier may be a field rather than a type. Without symbol
    // resolution, only unqualified calls have a provable same-file receiver.
    if (call.getScope().isPresent()) return failure(Status.EXTERNAL_OR_DYNAMIC);
    List<MethodDeclaration> candidates = owner.getMethodsByName(call.getNameAsString()).stream()
        .filter(m -> m.getParameters().size() == call.getArguments().size()).toList();
    if (candidates.isEmpty()) return failure(Status.EXTERNAL_OR_DYNAMIC);
    // An exact int/int[] signature wins over primitive widening or boxing.
    // Unknown argument types remain an ambiguity barrier.
    List<MethodDeclaration> exact = candidates.stream().filter(method -> exactSignature(call, outer, method)).toList();
    if (exact.size() > 1 || exact.isEmpty() && candidates.size() > 1) return failure(Status.AMBIGUOUS_OVERLOAD);
    MethodDeclaration method = exact.isEmpty() ? candidates.get(0) : exact.get(0);
    if ((!method.isStatic() && !method.isPrivate()) || method.getBody().isEmpty())
      return failure(Status.EXTERNAL_OR_DYNAMIC);
    String callableId = AstAnalyzer.nodeId(method);
    if (visited.contains(callableId)) return failure(Status.CYCLE);
    if (visited.size() >= MAX_CALL_DEPTH) return failure(Status.BUDGET_EXCEEDED);
    BlockStmt body = method.getBody().get();
    int count = statementCount + (int) body.findAll(Statement.class).stream().filter(s -> !(s instanceof BlockStmt)).count();
    if (count > MAX_VISITED_STATEMENTS) return failure(Status.BUDGET_EXCEEDED);
    if (!method.getType().asString().equals(comparison ? "boolean" : "void")) return failure(Status.UNRESOLVED);
    Map<String, Operand> arguments = new LinkedHashMap<>();
    for (int i = 0; i < method.getParameters().size(); i++) {
      Parameter parameter = method.getParameter(i);
      Optional<Operand> argument = operand(call.getArgument(i), outer);
      if (parameter.isVarArgs() || argument.isEmpty()) return failure(Status.UNRESOLVED);
      String type = parameter.getType().asString();
      boolean compatible = type.equals("int[]") ? arrayReference(argument.get()) : type.equals("int") && argument.get().index().isPresent();
      if (!compatible) return failure(Status.UNRESOLVED);
      arguments.put(AstAnalyzer.nodeId(parameter), argument.get());
    }
    Set<String> path = new LinkedHashSet<>(visited);
    path.add(callableId);
    if (body.getStatements().size() == 1) {
      Statement only = body.getStatement(0);
      Expression expression = comparison && only instanceof ReturnStmt ret ? ret.getExpression().orElse(null)
          : !comparison && only instanceof ExpressionStmt stmt ? stmt.getExpression() : null;
      if (SortSemanticFacts.unwrap(expression) instanceof MethodCallExpr nested) {
        Resolution<BodySummary> result = resolve(nested, arguments, path, count, comparison);
        if (result.value().isEmpty()) return result;
        BodySummary child = result.value().get();
        List<String> statements = new ArrayList<>(List.of(AstAnalyzer.nodeId(only)));
        statements.addAll(child.statements());
        List<String> callables = new ArrayList<>(List.of(callableId));
        callables.addAll(child.callables());
        return resolved(new BodySummary(child.left(), child.right(), List.copyOf(statements), List.copyOf(callables)));
      }
      if (comparison && expression != null) {
        Optional<SortSemanticFacts.NormalizedComparison> normalized = facts.strictComparison(expression);
        if (normalized.isEmpty()) return failure(hasEffects(body) ? Status.INTERFERING_EFFECT : Status.UNRESOLVED);
        Optional<Operand> left = operand(normalized.get().left(), arguments);
        Optional<Operand> right = operand(normalized.get().right(), arguments);
        if (left.isEmpty() || right.isEmpty() || left.get().index().isEmpty() || right.get().index().isEmpty()) return failure(Status.UNRESOLVED);
        return resolved(new BodySummary(left.get(), right.get(), List.of(AstAnalyzer.nodeId(only)), List.of(callableId)));
      }
    }
    if (comparison || body.getStatements().size() != 3)
      return failure(hasEffects(body) ? Status.INTERFERING_EFFECT : Status.UNRESOLVED);
    if (!(body.getStatement(0) instanceof ExpressionStmt first)
        || !(first.getExpression() instanceof VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return failure(Status.UNRESOLVED);
    VariableDeclarator temporary = declaration.getVariable(0);
    if (!temporary.getType().asString().equals("int") || temporary.getInitializer().isEmpty()) return failure(Status.UNRESOLVED);
    AssignExpr writeFirst = assignment(body.getStatement(1)), writeSecond = assignment(body.getStatement(2));
    if (writeFirst == null || writeSecond == null) return failure(Status.UNRESOLVED);
    Optional<Operand> saved = operand(temporary.getInitializer().get(), arguments);
    Optional<Operand> targetFirst = operand(writeFirst.getTarget(), arguments);
    Optional<Operand> sourceSecond = operand(writeFirst.getValue(), arguments);
    Optional<Operand> targetSecond = operand(writeSecond.getTarget(), arguments);
    Expression restored = SortSemanticFacts.unwrap(writeSecond.getValue());
    if (saved.isEmpty() || targetFirst.isEmpty() || sourceSecond.isEmpty() || targetSecond.isEmpty()
        || !arrayElement(saved.get()) || !arrayElement(sourceSecond.get())
        || !same(saved.get(), targetFirst.get()) || !same(sourceSecond.get(), targetSecond.get())
        || !sameSequence(saved.get(), sourceSecond.get())
        || !(restored instanceof NameExpr name)
        || !LocalVariableBindings.resolve(name).filter(AstAnalyzer.nodeId(temporary)::equals).isPresent()) return failure(Status.UNRESOLVED);
    return resolved(new BodySummary(saved.get(), sourceSecond.get(), body.getStatements().stream().map(AstAnalyzer::nodeId).toList(), List.of(callableId)));
  }

  private boolean exactSignature(MethodCallExpr call, Map<String, Operand> outer, MethodDeclaration method) {
    for (int i = 0; i < method.getParameters().size(); i++) {
      Parameter parameter = method.getParameter(i);
      Optional<Operand> argument = operand(call.getArgument(i), outer);
      if (parameter.isVarArgs() || argument.isEmpty()) return false;
      String type = parameter.getType().asString();
      if (!(type.equals("int[]") && arrayReference(argument.get())
          || type.equals("int") && argument.get().index().isPresent())) return false;
    }
    return true;
  }

  private Optional<Operand> operand(Expression expression, Map<String, Operand> arguments) {
    Expression value = SortSemanticFacts.unwrap(expression);
    if (value instanceof NameExpr name) {
      String id = LocalVariableBindings.resolve(name).orElse(null);
      if (id == null) return Optional.empty();
      if (arguments.containsKey(id)) return Optional.of(arguments.get(id));
      Optional<SortSemanticFacts.SequenceAccess> sequence = facts.sequenceAccess(name);
      if (sequence.isPresent() && sequence.get().binding().kind() == SortSemanticFacts.SequenceKind.INT_ARRAY)
        return Optional.of(new Operand(AstAnalyzer.nodeId(expression), Optional.of(sequence.get().binding()), Optional.empty()));
      return facts.indexOperand(name).map(index -> new Operand(AstAnalyzer.nodeId(expression), Optional.empty(), Optional.of(index)));
    }
    if (value instanceof ArrayAccessExpr access) {
      Optional<Operand> receiver = operand(access.getName(), arguments), index = operand(access.getIndex(), arguments);
      if (receiver.isEmpty() || !arrayReference(receiver.get()) || index.isEmpty() || index.get().sequence().isPresent() || index.get().index().isEmpty()) return Optional.empty();
      return Optional.of(new Operand(AstAnalyzer.nodeId(expression), receiver.get().sequence(), index.get().index()));
    }
    Optional<SortSemanticFacts.IndexOperand> index = facts.indexOperand(value);
    if (index.isEmpty()) return Optional.empty();
    SortSemanticFacts.IndexOperand local = index.get();
    Operand replacement = arguments.get(local.declarationId());
    if (replacement == null) return Optional.of(new Operand(AstAnalyzer.nodeId(expression), Optional.empty(), index));
    if (replacement.sequence().isPresent() || replacement.index().isEmpty()) return Optional.empty();
    SortSemanticFacts.IndexOperand actual = replacement.index().get();
    // Never combine offset arithmetic: no reassociation or overflow assumptions.
    if (actual.offset() != 0 && local.offset() != 0) return Optional.empty();
    return Optional.of(new Operand(AstAnalyzer.nodeId(expression), Optional.empty(), Optional.of(new SortSemanticFacts.IndexOperand(
        AstAnalyzer.nodeId(expression), actual.declarationId(), actual.displayName(), actual.offset() + local.offset()))));
  }

  private static AssignExpr assignment(Statement statement) {
    return statement instanceof ExpressionStmt expr && expr.getExpression() instanceof AssignExpr assign
        && assign.getOperator() == AssignExpr.Operator.ASSIGN ? assign : null;
  }
  private static boolean arrayReference(Operand operand) { return operand.sequence().isPresent() && operand.index().isEmpty(); }
  private static boolean arrayElement(Operand operand) { return operand.sequence().isPresent() && operand.index().isPresent(); }
  private static boolean sameSequence(Operand a, Operand b) {
    return a.sequence().isPresent() && b.sequence().isPresent()
        && a.sequence().get().canonicalDeclarationId().equals(b.sequence().get().canonicalDeclarationId());
  }
  private static boolean same(Operand a, Operand b) {
    return sameSequence(a,b) && a.index().isPresent() && b.index().isPresent()
        && a.index().get().declarationId().equals(b.index().get().declarationId()) && a.index().get().offset() == b.index().get().offset();
  }
  private static boolean hasEffects(Node body) {
    return !body.findAll(AssignExpr.class).isEmpty() || !body.findAll(MethodCallExpr.class).isEmpty()
        || !body.findAll(UnaryExpr.class).isEmpty() || !body.findAll(ObjectCreationExpr.class).isEmpty();
  }
  private record BodySummary(Operand left, Operand right, List<String> statements, List<String> callables) {}

  enum Status { RESOLVED, UNRESOLVED, EXTERNAL_OR_DYNAMIC, AMBIGUOUS_OVERLOAD, CYCLE, BUDGET_EXCEEDED, INTERFERING_EFFECT }
  record Resolution<T>(Status status, Optional<T> value, List<String> limitations) {
    Resolution { limitations = List.copyOf(limitations); }
  }
  record Operand(String astNodeId, Optional<SortSemanticFacts.SequenceBinding> sequence, Optional<SortSemanticFacts.IndexOperand> index) {}
  record Exchange(String callSiteId, String helperCallableId, SortSemanticFacts.SequenceBinding sequence,
      SortSemanticFacts.IndexOperand firstIndex, SortSemanticFacts.IndexOperand secondIndex,
      List<String> statementAstNodeIds, List<String> helperCallableIds) {
    Exchange { statementAstNodeIds = List.copyOf(statementAstNodeIds); helperCallableIds = List.copyOf(helperCallableIds); }
  }
  record Comparison(String callSiteId, String helperCallableId, Operand left, BinaryExpr.Operator operator, Operand right,
      List<String> statementAstNodeIds, List<String> helperCallableIds) {
    Comparison { statementAstNodeIds = List.copyOf(statementAstNodeIds); helperCallableIds = List.copyOf(helperCallableIds); }
  }
  private static <T> Resolution<T> failure(Status status) { return new Resolution<>(status, Optional.empty(), List.of(status.name())); }
  private static <T> Resolution<T> resolved(T value) { return new Resolution<>(Status.RESOLVED, Optional.of(value), List.of()); }
}
