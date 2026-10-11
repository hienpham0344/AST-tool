package com.example.astchunker.algorithm.sort;

import com.example.astchunker.ast.AstAnalyzer;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;

final class SortAstSupport {
  private SortAstSupport() {}

  static String counter(ForStmt loop, String initializer) {
    if (loop.getInitialization().size() != 1
        || loop.getUpdate().size() != 1
        || !(loop.getInitialization().get(0)
            instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return null;
    var variable = declaration.getVariable(0);
    String name = variable.getNameAsString();
    if (!variable.getType().asString().equals("int")
        || !matches(variable.getInitializer().orElse(null), initializer)) return null;
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

  static Statement single(Statement statement) {
    if (statement instanceof BlockStmt block && block.getStatements().size() == 1)
      return block.getStatement(0);
    return statement;
  }

  static boolean assignment(Statement statement, String target, String value) {
    return statement instanceof ExpressionStmt expression
        && expression.getExpression() instanceof AssignExpr assign
        && assign.getOperator() == AssignExpr.Operator.ASSIGN
        && matches(assign.getTarget(), target)
        && matches(assign.getValue(), value);
  }

  static boolean intArray(Node callable, String declarationId) {
    return callable.findAll(VariableDeclarator.class).stream()
            .anyMatch(
                variable ->
                    AstAnalyzer.nodeId(variable).equals(declarationId)
                        && variable.getType().asString().equals("int[]"))
        || callable.findAll(Parameter.class).stream()
            .anyMatch(
                parameter ->
                    AstAnalyzer.nodeId(parameter).equals(declarationId)
                        && parameter.getType().asString().equals("int[]"));
  }

  static boolean matches(Expression actual, String expected) {
    if (actual == null) return false;
    Expression copy = unwrap(actual).clone();
    for (EnclosedExpr enclosed : copy.findAll(EnclosedExpr.class))
      enclosed.replace(unwrap(enclosed).clone());
    return copy.equals(StaticJavaParser.parseExpression(expected));
  }

  static Expression unwrap(Expression expression) {
    while (expression instanceof EnclosedExpr enclosed) expression = enclosed.getInner();
    return expression;
  }
}
