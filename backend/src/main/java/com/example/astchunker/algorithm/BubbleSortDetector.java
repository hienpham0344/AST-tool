package com.example.astchunker.algorithm;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SortPattern;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Deliberately narrow: complete canonical loops, no extra side effects in their bodies. */
final class BubbleSortDetector {
  private BubbleSortDetector() {}

  static Optional<AlgorithmHint> detect(Node node) {
    if (!(node instanceof ForStmt outer)) return Optional.empty();
    String pass = counter(outer);
    Statement outerBody = single(outer.getBody());
    if (pass == null || !(outerBody instanceof ForStmt inner)) return Optional.empty();
    String scan = counter(inner);
    Statement innerBody = single(inner.getBody());
    if (scan == null
        || scan.equals(pass)
        || !(innerBody instanceof IfStmt branch)
        || branch.getElseStmt().isPresent()
        || !(unwrap(branch.getCondition()) instanceof BinaryExpr comparison))
      return Optional.empty();
    if (comparison.getOperator() != BinaryExpr.Operator.GREATER
        && comparison.getOperator() != BinaryExpr.Operator.LESS) return Optional.empty();
    if (!(unwrap(comparison.getLeft()) instanceof ArrayAccessExpr first)
        || !(unwrap(first.getName()) instanceof NameExpr arrayRef)) return Optional.empty();
    String array = arrayRef.getNameAsString();
    String a = array + "[" + scan + "]";
    String b = array + "[" + scan + " + 1]";
    if (!matches(first, a)
        || !matches(comparison.getRight(), b)
        || !matches(outer.getCompare().orElse(null), pass + " < " + array + ".length - 1")
        || !(matches(
                inner.getCompare().orElse(null), scan + " < " + array + ".length - 1 - " + pass)
            || matches(inner.getCompare().orElse(null), scan + " < " + array + ".length - 1"))) {
      return Optional.empty();
    }
    if (!(branch.getThenStmt() instanceof BlockStmt block) || block.getStatements().size() != 3)
      return Optional.empty();
    Statement save = block.getStatement(0);
    if (!(save instanceof ExpressionStmt expr)
        || !(expr.getExpression() instanceof VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return Optional.empty();
    VariableDeclarator temp = declaration.getVariable(0);
    if (!temp.getType().isPrimitiveType()
        || !temp.getType().asString().equals("int")
        || !matches(temp.getInitializer().orElse(null), a)
        || !assignment(block.getStatement(1), a, b)
        || !assignment(block.getStatement(2), b, temp.getNameAsString())) return Optional.empty();

    Node method = LocalVariableBindings.callable(outer);
    Map<String, String> roles =
        Map.of("array", array, "pass", pass, "scan", scan, "temp", temp.getNameAsString());
    var bound = LocalVariableBindings.roles(outer, roles);
    if (method == null || bound.isEmpty() || !intArray(method, bound.get().get("array")))
      return Optional.empty();
    var ids = bound.get();
    var range = outer.getRange().orElseThrow();
    var sort =
        new SortPattern(
            1,
            comparison.getOperator() == BinaryExpr.Operator.GREATER
                ? SortPattern.Direction.ASCENDING
                : SortPattern.Direction.DESCENDING,
            AstAnalyzer.nodeId(inner),
            AstAnalyzer.nodeId(comparison),
            AstAnalyzer.nodeId(branch),
            block.getStatements().stream().map(AstAnalyzer::nodeId).toList(),
            new SortPattern.Operand(ids.get("scan"), 0),
            new SortPattern.Operand(ids.get("scan"), 1));
    return Optional.of(
        new AlgorithmHint(
            "bubble-sort",
            0.95,
            "array-sort",
            List.of(
                "Nested forward loops compare adjacent array elements",
                "Strict comparison guards an ordered three-statement temporary swap",
                "All roles resolve to local or parameter declarations; no extra loop-body statements"),
            roles,
            range.begin.line,
            range.end.line,
            AstAnalyzer.nodeId(outer),
            AstAnalyzer.nodeId(method),
            ids,
            sort));
  }

  private static boolean intArray(Node method, String id) {
    return method.findAll(VariableDeclarator.class).stream()
            .anyMatch(
                v -> AstAnalyzer.nodeId(v).equals(id) && v.getType().asString().equals("int[]"))
        || method.findAll(Parameter.class).stream()
            .anyMatch(
                p -> AstAnalyzer.nodeId(p).equals(id) && p.getType().asString().equals("int[]"));
  }

  private static String counter(ForStmt loop) {
    if (loop.getInitialization().size() != 1
        || loop.getUpdate().size() != 1
        || !(loop.getInitialization().get(0) instanceof VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return null;
    var v = declaration.getVariable(0);
    String name = v.getNameAsString();
    if (!v.getType().asString().equals("int") || !matches(v.getInitializer().orElse(null), "0"))
      return null;
    Expression update = unwrap(loop.getUpdate().get(0));
    boolean increments =
        update instanceof UnaryExpr unary
            && matches(unary.getExpression(), name)
            && (unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
                || unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT);
    return increments
            || matches(update, name + " += 1")
            || matches(update, name + " = " + name + " + 1")
        ? name
        : null;
  }

  private static boolean assignment(Statement statement, String target, String value) {
    return statement instanceof ExpressionStmt expression
        && expression.getExpression() instanceof AssignExpr assign
        && assign.getOperator() == AssignExpr.Operator.ASSIGN
        && matches(assign.getTarget(), target)
        && matches(assign.getValue(), value);
  }

  private static Statement single(Statement statement) {
    if (statement instanceof BlockStmt block && block.getStatements().size() == 1)
      return block.getStatement(0);
    return statement;
  }

  private static Expression unwrap(Expression value) {
    while (value instanceof EnclosedExpr enclosed) value = enclosed.getInner();
    return value;
  }

  private static boolean matches(Expression actual, String expected) {
    if (actual == null) return false;
    // Expected expressions contain only identifiers taken from the AST and fixed syntax.
    Expression copy = unwrap(actual).clone();
    for (EnclosedExpr enclosed : copy.findAll(EnclosedExpr.class))
      enclosed.replace(unwrap(enclosed).clone());
    return copy.equals(StaticJavaParser.parseExpression(expected));
  }
}
