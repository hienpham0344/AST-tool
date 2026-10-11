package com.example.astchunker.algorithm.sort;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SortPattern;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Deliberately narrow: complete canonical loops, no extra side effects in their bodies. */
public final class BubbleSortDetector implements SortPatternDetector {
  public BubbleSortDetector() {}

  @Override
  public Optional<AlgorithmHint> detect(Node node) {
    if (!(node instanceof ForStmt outer)) return Optional.empty();
    String pass = SortAstSupport.counter(outer, "0");
    Statement outerBody = SortAstSupport.single(outer.getBody());
    if (pass == null || !(outerBody instanceof ForStmt inner)) return Optional.empty();
    String scan = SortAstSupport.counter(inner, "0");
    Statement innerBody = SortAstSupport.single(inner.getBody());
    if (scan == null
        || scan.equals(pass)
        || !(innerBody instanceof IfStmt branch)
        || branch.getElseStmt().isPresent()
        || !(SortAstSupport.unwrap(branch.getCondition()) instanceof BinaryExpr comparison))
      return Optional.empty();
    if (comparison.getOperator() != BinaryExpr.Operator.GREATER
        && comparison.getOperator() != BinaryExpr.Operator.LESS) return Optional.empty();
    if (!(SortAstSupport.unwrap(comparison.getLeft()) instanceof ArrayAccessExpr first)
        || !(SortAstSupport.unwrap(first.getName()) instanceof NameExpr arrayRef))
      return Optional.empty();
    String array = arrayRef.getNameAsString();
    String a = array + "[" + scan + "]";
    String b = array + "[" + scan + " + 1]";
    if (!SortAstSupport.matches(first, a)
        || !SortAstSupport.matches(comparison.getRight(), b)
        || !SortAstSupport.matches(
            outer.getCompare().orElse(null), pass + " < " + array + ".length - 1")
        || !(SortAstSupport.matches(
                inner.getCompare().orElse(null), scan + " < " + array + ".length - 1 - " + pass)
            || SortAstSupport.matches(
                inner.getCompare().orElse(null), scan + " < " + array + ".length - 1"))) {
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
        || !SortAstSupport.matches(temp.getInitializer().orElse(null), a)
        || !SortAstSupport.assignment(block.getStatement(1), a, b)
        || !SortAstSupport.assignment(block.getStatement(2), b, temp.getNameAsString()))
      return Optional.empty();

    Node method = LocalVariableBindings.callable(outer);
    Map<String, String> roles =
        Map.of("array", array, "pass", pass, "scan", scan, "temp", temp.getNameAsString());
    var bound = LocalVariableBindings.roles(outer, roles);
    if (method == null
        || bound.isEmpty()
        || !SortAstSupport.intArray(method, bound.get().get("array"))) return Optional.empty();
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
}
