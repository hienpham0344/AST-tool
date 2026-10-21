package com.example.astchunker.algorithm.dfs;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.DfsPattern;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import java.util.*;

/**
 * A deliberately closed structural proof: no aliases, opaque calls or alternate traversal paths.
 */
public final class DfsDetector {
  public Optional<AlgorithmHint> detect(CompilationUnit unit, MethodDeclaration method) {
    if (method.getBody().isEmpty()
        || !method.getType().isVoidType()
        || method.getParameters().size() != 3
        || !method.getTypeParameters().isEmpty()
        || !unambiguous(method)) return Optional.empty();
    Parameter graph = null;
    Parameter visited = null;
    Parameter current = null;
    String representation = null;
    for (Parameter parameter : method.getParameters()) {
      if (parameter.isVarArgs()) return Optional.empty();
      String type = parameter.getType().asString();
      if (type.equals("boolean[]") && visited == null) visited = parameter;
      else if (type.equals("int") && current == null) current = parameter;
      else if (graph == null && type.equals("int[][]")) {
        graph = parameter;
        representation = "ADJACENCY_MATRIX";
      } else if (graph == null && listType(unit, parameter.getType())) {
        graph = parameter;
        representation = "ADJACENCY_LIST";
      } else return Optional.empty();
    }
    if (graph == null || visited == null || current == null) return Optional.empty();
    List<Statement> body = method.getBody().get().getStatements();
    int position = 0;
    if (!body.isEmpty() && entryGuard(body.get(0), visited, current)) position++;
    while (position < body.size() && scalarLocal(body.get(position))) position++;
    if (position >= body.size() || !mark(body.get(position), visited, current))
      return Optional.empty();
    AssignExpr mark = (AssignExpr) expression(body.get(position++));
    while (position < body.size() && scalarLocal(body.get(position))) position++;
    if (position >= body.size()) return Optional.empty();
    Statement loop = body.get(position++);
    while (position < body.size() && scalarLocal(body.get(position))) position++;
    if (position != body.size()) return Optional.empty();

    VariableDeclarator neighbor;
    Statement neighborBody;
    if (representation.equals("ADJACENCY_MATRIX") && loop instanceof ForStmt scan) {
      if (scan.getInitialization().size() != 1
          || !(scan.getInitialization().get(0) instanceof VariableDeclarationExpr variables)
          || variables.getVariables().size() != 1
          || scan.getCompare().isEmpty()
          || scan.getUpdate().size() != 1) return Optional.empty();
      neighbor = variables.getVariable(0);
      if (!neighbor.getInitializer().filter(DfsDetector::zero).isPresent()
          || !(unwrap(scan.getCompare().get()) instanceof BinaryExpr bound)
          || bound.getOperator() != BinaryExpr.Operator.LESS
          || !ref(bound.getLeft(), neighbor)
          || !(unwrap(bound.getRight()) instanceof FieldAccessExpr length)
          || !length.getNameAsString().equals("length")
          || !ref(length.getScope(), graph)
          || !(unwrap(scan.getUpdate().get(0)) instanceof UnaryExpr increment)
          || !Set.of(UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.PREFIX_INCREMENT)
              .contains(increment.getOperator())
          || !ref(increment.getExpression(), neighbor)) return Optional.empty();
      neighborBody = scan.getBody();
    } else if (representation.equals("ADJACENCY_LIST") && loop instanceof ForEachStmt each) {
      if (each.getVariable().getVariables().size() != 1
          || !(unwrap(each.getIterable()) instanceof MethodCallExpr get)
          || !get.getNameAsString().equals("get")
          || get.getArguments().size() != 1
          || !ref(get.getScope().orElse(null), graph)
          || !ref(get.getArgument(0), current)
          || get.getTypeArguments().isPresent()) return Optional.empty();
      neighbor = each.getVariable().getVariable(0);
      neighborBody = each.getBody();
    } else return Optional.empty();
    if (!neighbor.getType().asString().equals("int")) return Optional.empty();
    List<Statement> neighborSteps = statements(neighborBody);
    if (neighborSteps.size() != 1
        || !(neighborSteps.get(0) instanceof IfStmt guard)
        || guard.getElseStmt().isPresent()) return Optional.empty();
    Expression test = unwrap(guard.getCondition());
    if (representation.equals("ADJACENCY_MATRIX")) {
      if (!(test instanceof BinaryExpr and) || and.getOperator() != BinaryExpr.Operator.AND)
        return Optional.empty();
      if (edge(and.getLeft(), graph, current, neighbor)) test = unwrap(and.getRight());
      else if (edge(and.getRight(), graph, current, neighbor)) test = unwrap(and.getLeft());
      else return Optional.empty();
    }
    if (!unseen(test, visited, neighbor)) return Optional.empty();
    List<Statement> descent = statements(guard.getThenStmt());
    if (descent.size() != 1
        || !(expression(descent.get(0)) instanceof MethodCallExpr recursive)
        || recursive.getScope().isPresent()
        || recursive.getTypeArguments().isPresent()
        || !recursive.getNameAsString().equals(method.getNameAsString())
        || recursive.getArguments().size() != method.getParameters().size())
      return Optional.empty();
    for (int i = 0; i < method.getParameters().size(); i++) {
      Parameter expected = method.getParameter(i);
      if (!ref(recursive.getArgument(i), expected == current ? neighbor : expected))
        return Optional.empty();
    }
    Map<String, String> names =
        Map.of(
            "graph",
            graph.getNameAsString(),
            "visited",
            visited.getNameAsString(),
            "current",
            current.getNameAsString(),
            "neighbor",
            neighbor.getNameAsString());
    Map<String, String> ids =
        Map.of(
            "graph",
            AstAnalyzer.nodeId(graph),
            "visited",
            AstAnalyzer.nodeId(visited),
            "current",
            AstAnalyzer.nodeId(current),
            "neighbor",
            AstAnalyzer.nodeId(neighbor));
    List<String> evidence =
        List.of(
            "Current vertex is marked before visiting neighbors",
            "Each unseen adjacent vertex directly recurses into the same source method with"
                + " unchanged graph and visited");
    DfsPattern pattern =
        new DfsPattern(
            1,
            representation,
            ids,
            Map.of(
                "mark",
                AstAnalyzer.nodeId(mark),
                "neighborLoop",
                AstAnalyzer.nodeId(loop),
                "guard",
                AstAnalyzer.nodeId(guard),
                "recursiveCall",
                AstAnalyzer.nodeId(recursive)),
            evidence);
    var range = method.getRange().orElseThrow();
    return Optional.of(
        new AlgorithmHint(
            "dfs",
            .95,
            "graph-dfs",
            evidence,
            names,
            range.begin.line,
            range.end.line,
            AstAnalyzer.nodeId(method),
            AstAnalyzer.nodeId(method),
            ids,
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
            pattern));
  }

  private static boolean unambiguous(MethodDeclaration method) {
    if (!(method.getParentNode().orElse(null) instanceof ClassOrInterfaceDeclaration owner)
        || owner.isInterface()
        || !owner.getExtendedTypes().isEmpty()
        || !owner.getImplementedTypes().isEmpty()) return false;
    if (!method.isStatic() && !method.isPrivate() && !method.isFinal() && !owner.isFinal())
      return false;
    return owner.getMethodsByName(method.getNameAsString()).size() == 1;
  }

  private static boolean entryGuard(Statement statement, Parameter visited, Parameter current) {
    if (!(statement instanceof IfStmt guard)
        || guard.getElseStmt().isPresent()
        || !(unwrap(guard.getCondition()) instanceof ArrayAccessExpr access)
        || !ref(access.getName(), visited)
        || !ref(access.getIndex(), current)) return false;
    List<Statement> steps = statements(guard.getThenStmt());
    return steps.size() == 1
        && steps.get(0) instanceof ReturnStmt exit
        && exit.getExpression().isEmpty();
  }

  private static boolean mark(Statement statement, Parameter visited, Parameter current) {
    return expression(statement) instanceof AssignExpr assign
        && assign.getOperator() == AssignExpr.Operator.ASSIGN
        && unwrap(assign.getTarget()) instanceof ArrayAccessExpr access
        && ref(access.getName(), visited)
        && ref(access.getIndex(), current)
        && unwrap(assign.getValue()) instanceof BooleanLiteralExpr value
        && value.getValue();
  }

  private static boolean edge(
      Expression expression, Parameter graph, Parameter current, VariableDeclarator neighbor) {
    if (!(unwrap(expression) instanceof BinaryExpr comparison)
        || comparison.getOperator() != BinaryExpr.Operator.NOT_EQUALS) return false;
    Expression access =
        zero(comparison.getRight())
            ? comparison.getLeft()
            : zero(comparison.getLeft()) ? comparison.getRight() : null;
    return unwrap(access) instanceof ArrayAccessExpr outer
        && ref(outer.getIndex(), neighbor)
        && unwrap(outer.getName()) instanceof ArrayAccessExpr inner
        && ref(inner.getName(), graph)
        && ref(inner.getIndex(), current);
  }

  private static boolean unseen(
      Expression expression, Parameter visited, VariableDeclarator neighbor) {
    return unwrap(expression) instanceof UnaryExpr unary
        && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
        && unwrap(unary.getExpression()) instanceof ArrayAccessExpr access
        && ref(access.getName(), visited)
        && ref(access.getIndex(), neighbor);
  }

  // Only inert primitive locals are admitted around the proof; graph/visited cannot escape here.
  private static boolean scalarLocal(Statement statement) {
    return expression(statement) instanceof VariableDeclarationExpr declaration
        && declaration.getVariables().stream()
            .allMatch(
                variable ->
                    variable.getType().isPrimitiveType()
                        && variable.getInitializer().filter(DfsDetector::pureScalar).isPresent());
  }

  private static boolean pureScalar(Expression expression) {
    Expression value = unwrap(expression);
    if (value instanceof LiteralExpr) return !(value instanceof NullLiteralExpr);
    if (value instanceof NameExpr name) {
      String id = LocalVariableBindings.resolve(name).orElse(null);
      Node callable = LocalVariableBindings.callable(name);
      if (id == null || callable == null) return false;
      return callable.findAll(Parameter.class).stream()
              .anyMatch(p -> p.getType().isPrimitiveType() && AstAnalyzer.nodeId(p).equals(id))
          || callable.findAll(VariableDeclarator.class).stream()
              .anyMatch(v -> v.getType().isPrimitiveType() && AstAnalyzer.nodeId(v).equals(id));
    }
    if (value instanceof BinaryExpr binary)
      return pureScalar(binary.getLeft()) && pureScalar(binary.getRight());
    return value instanceof UnaryExpr unary
        && Set.of(
                UnaryExpr.Operator.PLUS,
                UnaryExpr.Operator.MINUS,
                UnaryExpr.Operator.BITWISE_COMPLEMENT,
                UnaryExpr.Operator.LOGICAL_COMPLEMENT)
            .contains(unary.getOperator())
        && pureScalar(unary.getExpression());
  }

  private static boolean listType(CompilationUnit unit, Type type) {
    if (!(type instanceof ClassOrInterfaceType outer)
        || !jdk(unit, outer.getNameWithScope(), "List", "java.util")
        || outer.getTypeArguments().isEmpty()
        || outer.getTypeArguments().get().size() != 1
        || !(outer.getTypeArguments().get().get(0) instanceof ClassOrInterfaceType inner)
        || !jdk(unit, inner.getNameWithScope(), "List", "java.util")
        || inner.getTypeArguments().isEmpty()
        || inner.getTypeArguments().get().size() != 1
        || !(inner.getTypeArguments().get().get(0) instanceof ClassOrInterfaceType integer))
      return false;
    return integer.getTypeArguments().isEmpty()
        && jdk(unit, integer.getNameWithScope(), "Integer", "java.lang");
  }

  private static boolean jdk(CompilationUnit unit, String name, String simple, String pkg) {
    // Even a fully qualified spelling can be captured by a source/imported type named java.
    if (unit.findAll(TypeDeclaration.class).stream()
            .anyMatch(t -> t.getNameAsString().equals("java"))
        || unit.findAll(TypeParameter.class).stream()
            .anyMatch(t -> t.getNameAsString().equals("java"))
        || unit.getImports().stream()
            .anyMatch(
                i ->
                    i.getName().getIdentifier().equals("java")
                        || i.isAsterisk()
                            && !Set.of("java.util", "java.lang").contains(i.getNameAsString())))
      return false;
    if (name.equals(pkg + "." + simple)) return true;
    if (!name.equals(simple)
        || unit.findAll(TypeDeclaration.class).stream()
            .anyMatch(t -> t.getNameAsString().equals(simple))
        || unit.findAll(TypeParameter.class).stream()
            .anyMatch(t -> t.getNameAsString().equals(simple))) return false;
    if (unit.getImports().stream()
        .anyMatch(
            i ->
                i.isStatic() && (i.isAsterisk() || i.getName().getIdentifier().equals(simple))
                    || !i.isStatic()
                        && !i.isAsterisk()
                        && i.getName().getIdentifier().equals(simple)
                        && !i.getNameAsString().equals(pkg + "." + simple)
                    || i.isAsterisk()
                        && !Set.of("java.util", "java.lang").contains(i.getNameAsString())))
      return false;
    return pkg.equals("java.lang")
        || unit.getImports().stream()
            .anyMatch(
                i ->
                    !i.isStatic()
                        && (i.getNameAsString().equals(pkg + "." + simple)
                            || i.isAsterisk() && i.getNameAsString().equals(pkg)));
  }

  private static boolean ref(Expression expression, Node declaration) {
    return unwrap(expression) instanceof NameExpr name
        && LocalVariableBindings.resolve(name)
            .filter(AstAnalyzer.nodeId(declaration)::equals)
            .isPresent();
  }

  private static boolean zero(Expression expression) {
    return unwrap(expression) instanceof IntegerLiteralExpr literal
        && literal.asNumber().intValue() == 0;
  }

  private static Expression expression(Statement statement) {
    return statement instanceof ExpressionStmt value ? value.getExpression() : null;
  }

  private static List<Statement> statements(Statement statement) {
    return statement instanceof BlockStmt block ? block.getStatements() : List.of(statement);
  }

  private static Expression unwrap(Expression expression) {
    while (expression instanceof EnclosedExpr enclosed) expression = enclosed.getInner();
    return expression;
  }
}
