package com.example.astchunker.algorithm.sort;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SortPattern;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Detects a canonical shift-and-insert insertion-sort pass over an int array. */
public final class InsertionSortDetector implements SortPatternDetector {
  @Override
  public Optional<AlgorithmHint> detect(Node node) {
    if (!(node instanceof ForStmt outer)
        || !(outer.getBody() instanceof BlockStmt body)
        || body.getStatements().size() != 4) return Optional.empty();

    String pass = SortAstSupport.counter(outer, "1");
    if (pass == null
        || !(body.getStatement(0) instanceof ExpressionStmt keyStatement)
        || !(keyStatement.getExpression()
            instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr keyDeclaration)
        || keyDeclaration.getVariables().size() != 1
        || !(body.getStatement(1) instanceof ExpressionStmt scanStatement)
        || !(scanStatement.getExpression()
            instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr scanDeclaration)
        || scanDeclaration.getVariables().size() != 1
        || !(body.getStatement(2) instanceof WhileStmt shifting)
        || !(shifting.getBody() instanceof BlockStmt shiftBody)
        || shiftBody.getStatements().size() != 2) return Optional.empty();

    VariableDeclarator key = keyDeclaration.getVariable(0);
    VariableDeclarator scan = scanDeclaration.getVariable(0);
    String keyName = key.getNameAsString();
    String scanName = scan.getNameAsString();
    if (keyName.equals(pass)
        || keyName.equals(scanName)
        || scanName.equals(pass)
        || !key.getType().asString().equals("int")
        || !scan.getType().asString().equals("int")) return Optional.empty();

    if (!(key.getInitializer().orElse(null) instanceof ArrayAccessExpr keyRead)
        || !(keyRead.getName() instanceof NameExpr arrayRef)) return Optional.empty();
    String array = arrayRef.getNameAsString();
    if (!SortAstSupport.matches(keyRead.getIndex(), pass)
        || !SortAstSupport.matches(scan.getInitializer().orElse(null), pass + " - 1")
        || !SortAstSupport.matches(
            outer.getCompare().orElse(null), pass + " < " + array + ".length"))
      return Optional.empty();

    String arrayAtScan = array + "[" + scanName + "]";
    String shiftedSlot = array + "[" + scanName + " + 1]";
    if (!SortAstSupport.assignment(shiftBody.getStatement(0), shiftedSlot, arrayAtScan)
        || !decrements(shiftBody.getStatement(1), scanName)
        || !SortAstSupport.assignment(body.getStatement(3), shiftedSlot, keyName))
      return Optional.empty();

    Condition condition = shiftCondition(shifting.getCondition(), scanName, array, keyName);
    if (condition == null) return Optional.empty();
    Node method = LocalVariableBindings.callable(outer);
    Map<String, String> roles =
        Map.of("array", array, "pass", pass, "scan", scanName, "key", keyName);
    var bound = LocalVariableBindings.roles(outer, roles);
    if (method == null
        || bound.isEmpty()
        || !SortAstSupport.intArray(method, bound.get().get("array"))) return Optional.empty();

    var ids = bound.get();
    var range = outer.getRange().orElseThrow();
    var pattern =
        new SortPattern(
            1,
            condition.direction(),
            AstAnalyzer.nodeId(shifting),
            AstAnalyzer.nodeId(condition.comparison()),
            AstAnalyzer.nodeId(shifting),
            List.of(),
            new SortPattern.Operand(ids.get("scan"), 0),
            new SortPattern.Operand(ids.get("pass"), 0),
            Map.of(
                "shiftWrite", AstAnalyzer.nodeId(shiftBody.getStatement(0)),
                "scanDecrement", AstAnalyzer.nodeId(shiftBody.getStatement(1)),
                "insertWrite", AstAnalyzer.nodeId(body.getStatement(3))));
    return Optional.of(
        new AlgorithmHint(
            "insertion-sort",
            0.96,
            "array-sort",
            List.of(
                "An outer pass saves the next value as a key",
                "A guarded backward scan shifts larger (or smaller) values one slot to the right",
                "The saved key is written into the gap after shifting stops"),
            roles,
            range.begin.line,
            range.end.line,
            AstAnalyzer.nodeId(outer),
            AstAnalyzer.nodeId(method),
            ids,
            pattern));
  }

  private Condition shiftCondition(Expression expression, String scan, String array, String key) {
    Expression unwrapped = SortAstSupport.unwrap(expression);
    if (!(unwrapped instanceof BinaryExpr conjunction)
        || conjunction.getOperator() != BinaryExpr.Operator.AND) return null;
    Expression left = SortAstSupport.unwrap(conjunction.getLeft());
    Expression right = SortAstSupport.unwrap(conjunction.getRight());
    if (!(left instanceof BinaryExpr boundary)
        || boundary.getOperator() != BinaryExpr.Operator.GREATER_EQUALS
        || !SortAstSupport.matches(boundary.getLeft(), scan)
        || !SortAstSupport.matches(boundary.getRight(), "0")
        || !(right instanceof BinaryExpr comparison)) return null;

    Expression first = SortAstSupport.unwrap(comparison.getLeft());
    Expression second = SortAstSupport.unwrap(comparison.getRight());
    boolean arrayOnLeft = arrayAccess(first, array, scan) && SortAstSupport.matches(second, key);
    boolean keyOnLeft = SortAstSupport.matches(first, key) && arrayAccess(second, array, scan);
    if (!arrayOnLeft && !keyOnLeft) return null;
    boolean ascending =
        arrayOnLeft
            ? comparison.getOperator() == BinaryExpr.Operator.GREATER
            : comparison.getOperator() == BinaryExpr.Operator.LESS;
    boolean descending =
        arrayOnLeft
            ? comparison.getOperator() == BinaryExpr.Operator.LESS
            : comparison.getOperator() == BinaryExpr.Operator.GREATER;
    if (!ascending && !descending) return null;
    return new Condition(
        comparison, ascending ? SortPattern.Direction.ASCENDING : SortPattern.Direction.DESCENDING);
  }

  private boolean arrayAccess(Expression expression, String array, String index) {
    return expression instanceof ArrayAccessExpr access
        && SortAstSupport.matches(access.getName(), array)
        && SortAstSupport.matches(access.getIndex(), index);
  }

  private boolean decrements(Statement statement, String variable) {
    if (!(statement instanceof ExpressionStmt expression)) return false;
    Expression value = SortAstSupport.unwrap(expression.getExpression());
    if (value instanceof UnaryExpr unary) {
      return SortAstSupport.matches(unary.getExpression(), variable)
          && (unary.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT
              || unary.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT);
    }
    if (value instanceof com.github.javaparser.ast.expr.AssignExpr assignment) {
      return assignment.getOperator() == com.github.javaparser.ast.expr.AssignExpr.Operator.MINUS
              && SortAstSupport.matches(assignment.getTarget(), variable)
              && SortAstSupport.matches(assignment.getValue(), "1")
          || assignment.getOperator() == com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN
              && SortAstSupport.matches(assignment.getTarget(), variable)
              && SortAstSupport.matches(assignment.getValue(), variable + " - 1");
    }
    return false;
  }

  private record Condition(BinaryExpr comparison, SortPattern.Direction direction) {}
}
