package com.example.astchunker.algorithm.sort;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SortPattern;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Detects a canonical min/max selection pass and its three-statement final swap. */
public final class SelectionSortDetector implements SortPatternDetector {
  public SelectionSortDetector() {}

  @Override
  public Optional<AlgorithmHint> detect(Node node) {
    if (!(node instanceof ForStmt outer)) return Optional.empty();
    String pass = SortAstSupport.counter(outer, "0");
    if (pass == null
        || !(outer.getBody() instanceof BlockStmt outerBody)
        || outerBody.getStatements().size() != 5
        || !(outerBody.getStatement(0) instanceof ExpressionStmt declarationStatement)
        || !(declarationStatement.getExpression() instanceof VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1
        || !(outerBody.getStatement(1) instanceof ForStmt inner)) return Optional.empty();

    VariableDeclarator selected = declaration.getVariable(0);
    String selectedIndex = selected.getNameAsString();
    if (!selected.getType().asString().equals("int")
        || !SortAstSupport.matches(selected.getInitializer().orElse(null), pass))
      return Optional.empty();

    String scan = SortAstSupport.counter(inner, pass + " + 1");
    Statement innerBody = SortAstSupport.single(inner.getBody());
    if (scan == null
        || !(innerBody instanceof IfStmt branch)
        || branch.getElseStmt().isPresent()
        || !(SortAstSupport.single(branch.getThenStmt()) instanceof ExpressionStmt update)
        || !(update.getExpression() instanceof com.github.javaparser.ast.expr.AssignExpr assign)
        || assign.getOperator() != com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN
        || !SortAstSupport.matches(assign.getTarget(), selectedIndex)
        || !SortAstSupport.matches(assign.getValue(), scan)
        || !(branch.getCondition() instanceof BinaryExpr comparison)
        || comparison.getOperator() != BinaryExpr.Operator.LESS
            && comparison.getOperator() != BinaryExpr.Operator.GREATER
        || !(comparison.getLeft() instanceof ArrayAccessExpr first)
        || !(first.getName() instanceof NameExpr arrayName)) return Optional.empty();

    String array = arrayName.getNameAsString();
    if (!SortAstSupport.matches(first.getIndex(), scan)
        || !(comparison.getRight() instanceof ArrayAccessExpr second)
        || !SortAstSupport.matches(second.getName(), array)
        || !SortAstSupport.matches(second.getIndex(), selectedIndex)
        || !SortAstSupport.matches(
            inner.getCompare().orElse(null), scan + " < " + array + ".length")
        || !SortAstSupport.matches(
            outer.getCompare().orElse(null), pass + " < " + array + ".length - 1")
        || !(outerBody.getStatement(2) instanceof ExpressionStmt tempStatement)
        || !(tempStatement.getExpression() instanceof VariableDeclarationExpr tempDeclaration)
        || tempDeclaration.getVariables().size() != 1) return Optional.empty();

    VariableDeclarator temp = tempDeclaration.getVariable(0);
    String tempName = temp.getNameAsString();
    String left = array + "[" + pass + "]";
    String right = array + "[" + selectedIndex + "]";
    if (!temp.getType().asString().equals("int")
        || !SortAstSupport.matches(temp.getInitializer().orElse(null), left)
        || !SortAstSupport.assignment(outerBody.getStatement(3), left, right)
        || !SortAstSupport.assignment(outerBody.getStatement(4), right, tempName))
      return Optional.empty();

    Node method = LocalVariableBindings.callable(outer);
    Map<String, String> roles =
        Map.of(
            "array", array,
            "pass", pass,
            "scan", scan,
            "selected", selectedIndex,
            "temp", tempName);
    var bound = LocalVariableBindings.roles(outer, roles);
    if (method == null
        || bound.isEmpty()
        || !SortAstSupport.matches(comparison.getRight(), array + "[" + selectedIndex + "]")
        || !SortAstSupport.intArray(method, bound.get().get("array"))) return Optional.empty();

    var ids = bound.get();
    var range = outer.getRange().orElseThrow();
    var pattern =
        new SortPattern(
            1,
            comparison.getOperator() == BinaryExpr.Operator.LESS
                ? SortPattern.Direction.ASCENDING
                : SortPattern.Direction.DESCENDING,
            AstAnalyzer.nodeId(inner),
            AstAnalyzer.nodeId(comparison),
            AstAnalyzer.nodeId(branch),
            outerBody.getStatements().subList(2, 5).stream().map(AstAnalyzer::nodeId).toList(),
            new SortPattern.Operand(ids.get("scan"), 0),
            new SortPattern.Operand(ids.get("selected"), 0),
            Map.of(
                "selectedIndexUpdate",
                AstAnalyzer.nodeId(SortAstSupport.single(branch.getThenStmt()))));
    return Optional.of(
        new AlgorithmHint(
            "selection-sort",
            0.96,
            "array-sort",
            List.of(
                "An outer pass initializes a selected index from the current boundary",
                "An inner forward scan compares array values and conditionally replaces that index",
                "The pass ends with a canonical three-statement temporary swap"),
            roles,
            range.begin.line,
            range.end.line,
            AstAnalyzer.nodeId(outer),
            AstAnalyzer.nodeId(method),
            ids,
            pattern));
  }
}
