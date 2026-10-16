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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

  @Override
  public Optional<AlgorithmHint> detect(Node node, SortPatternRegistry.Session session) {
    if (!(node instanceof ForStmt outer)) return Optional.empty();
    var facts = session.facts();
    var pass = facts.forwardUnitCounter(outer);
    if (pass.isEmpty() || !zero(pass.get().initializer())) return Optional.empty();

    List<Statement> outerStatements = statements(outer.getBody());
    boolean earlyExit = outerStatements.size() == 3;
    if (!earlyExit && outerStatements.size() != 1) return Optional.empty();
    VariableDeclarator flag = earlyExit ? falseFlag(outerStatements.get(0)) : null;
    if (earlyExit && flag == null) return Optional.empty();
    Statement innerStatement = outerStatements.get(earlyExit ? 1 : 0);
    if (!(innerStatement instanceof ForStmt inner)) return Optional.empty();
    if (earlyExit && !breakOnFalse(outerStatements.get(2), AstAnalyzer.nodeId(flag)))
      return Optional.empty();

    var scan = facts.forwardUnitCounter(inner);
    if (scan.isEmpty() || !zero(scan.get().initializer())
        || scan.get().declarationId().equals(pass.get().declarationId())) return Optional.empty();
    List<Statement> innerStatements = statements(inner.getBody());
    IfStmt branch = null;
    for (Statement statement : innerStatements) {
      if (statement instanceof IfStmt candidate) {
        if (branch != null) return Optional.empty();
        branch = candidate;
      } else if (!harmlessScalar(statement, pass.get(), scan.get(), facts)) return Optional.empty();
    }
    if (branch == null || branch.getElseStmt().isPresent()) return Optional.empty();

    var primitiveComparison = facts.strictComparison(branch.getCondition());
    var comparatorComparison = primitiveComparison.isEmpty()
        ? facts.strictComparatorComparison(branch.getCondition()) : Optional.<SortSemanticFacts.NormalizedComparatorComparison>empty();
    if (primitiveComparison.isEmpty() && comparatorComparison.isEmpty()) return Optional.empty();
    Expression comparisonLeft;
    Expression comparisonRight;
    SortSemanticFacts.ComparatorBinding comparator = null;
    if (primitiveComparison.isPresent()) {
      comparisonLeft = primitiveComparison.get().left();
      comparisonRight = primitiveComparison.get().right();
    } else {
      var normalized = comparatorComparison.get();
      comparisonLeft = normalized.left();
      comparisonRight = normalized.right();
      comparator = normalized.comparator();
      if (normalized.operator() == BinaryExpr.Operator.LESS) {
        Expression reversed = comparisonLeft;
        comparisonLeft = comparisonRight;
        comparisonRight = reversed;
      }
    }
    var left = element(comparisonLeft, facts);
    var right = element(comparisonRight, facts);
    if (left.isEmpty() || right.isEmpty()) return Optional.empty();
    var first = left.get();
    var second = right.get();
    if (!(first.binding().isNumericPrimitiveArray()
            || comparator != null && comparator.supports(first.binding()))
        || !first.binding().canonicalDeclarationId().equals(second.binding().canonicalDeclarationId())
        || !first.index().declarationId().equals(scan.get().declarationId())
        || !second.index().declarationId().equals(scan.get().declarationId())) return Optional.empty();
    SortPattern.Direction direction;
    if (first.index().offset() == 0 && second.index().offset() == 1)
      direction = SortPattern.Direction.ASCENDING;
    else if (first.index().offset() == 1 && second.index().offset() == 0)
      direction = SortPattern.Direction.DESCENDING;
    else return Optional.empty();

    var sequence = first.binding();
    var outerBound = facts.supportedLengthBound(outer, sequence);
    var innerBound = facts.supportedLengthBound(inner, sequence);
    if (outerBound.isEmpty() || innerBound.isEmpty()
        || outerBound.get().lengthOffset() != -1 || outerBound.get().pass().isPresent()
        || innerBound.get().lengthOffset() != -1
        || innerBound.get().pass().isPresent()
            && !innerBound.get().pass().get().declarationId().equals(pass.get().declarationId()))
      return Optional.empty();

    List<Statement> branchStatements = statements(branch.getThenStmt());
    if (branchStatements.isEmpty()) return Optional.empty();
    VariableDeclarator temp = directSwap(branchStatements, sequence, scan.get(), facts, flag);
    SameFileHelperSummaries.Exchange helper = null;
    MethodCallExpr helperCall = null;
    if (temp == null && branchStatements.size() == (earlyExit ? 2 : 1)
        && branchStatements.get(0) instanceof ExpressionStmt expression
        && expression.getExpression() instanceof MethodCallExpr call
        && (!earlyExit || setsFlag(branchStatements.get(1), flag))) {
      var resolved = session.helpers().resolveExchange(call);
      if (resolved.value().isPresent() && sameExchange(resolved.value().get(), sequence, scan.get())) {
        helper = resolved.value().get();
        helperCall = call;
      }
    }
    if (temp == null && helper == null) return Optional.empty();

    Set<String> allowed = new LinkedHashSet<>();
    for (Statement statement : branchStatements) allowed.add(AstAnalyzer.nodeId(statement));
    if (facts.hasInterferingMutation(branch.getThenStmt(), sequence, allowed)) return Optional.empty();
    if (earlyExit) {
      String flagId = AstAnalyzer.nodeId(flag);
      long writes = outer.getBody().findAll(AssignExpr.class).stream()
          .filter(assign -> SortSemanticFacts.unwrap(assign.getTarget()) instanceof NameExpr name
              && LocalVariableBindings.resolve(name).filter(flagId::equals).isPresent())
          .count();
      if (writes != 1) return Optional.empty();
    }

    Node method = LocalVariableBindings.callable(outer);
    if (method == null) return Optional.empty();
    Map<String, String> roles = new LinkedHashMap<>();
    Map<String, String> ids = new LinkedHashMap<>();
    roles.put("array", sequence.displayName());
    roles.put("pass", pass.get().displayName());
    roles.put("scan", scan.get().displayName());
    ids.put("array", sequence.sourceDeclarationId());
    ids.put("pass", pass.get().declarationId());
    ids.put("scan", scan.get().declarationId());
    if (comparator != null) {
      roles.put("comparator", comparator.displayName());
      ids.put("comparator", comparator.declarationId());
    }
    if (temp != null) {
      roles.put("temp", temp.getNameAsString());
      ids.put("temp", AstAnalyzer.nodeId(temp));
    }
    if (flag != null) {
      roles.put("swapped", flag.getNameAsString());
      ids.put("swapped", AstAnalyzer.nodeId(flag));
    }
    var range = outer.getRange().orElse(null);
    if (range == null) return Optional.empty();
    SortPattern sort = temp == null || sequence.kind() != SortSemanticFacts.SequenceKind.INT_ARRAY
        ? null : new SortPattern(
        1, direction, AstAnalyzer.nodeId(inner),
        AstAnalyzer.nodeId(SortSemanticFacts.unwrap(branch.getCondition())),
        AstAnalyzer.nodeId(branch),
        branchStatements.subList(0, 3).stream().map(AstAnalyzer::nodeId).toList(),
        new SortPattern.Operand(scan.get().declarationId(), 0),
        new SortPattern.Operand(scan.get().declarationId(), 1));
    List<String> evidence = new ArrayList<>(List.of(
        comparator == null
            ? "Two forward passes share one numeric primitive array receiver and trusted length bounds"
            : "Two forward passes share one comparator-bound reference array receiver and trusted length bounds",
        "A strict comparison controls an adjacent exchange"));
    if (comparator != null) evidence.add("Direct Comparator parameter " + comparator.declarationId()
        + " compares the two adjacent elements");
    if (helper != null) evidence.add("Resolved same-file exchange helper "
        + helper.helperCallableId() + " at " + AstAnalyzer.nodeId(helperCall));
    else evidence.add("Three caller statements prove the temporary exchange");
    if (earlyExit) evidence.add("Per-pass swapped flag controls the matching break");
    return Optional.of(new AlgorithmHint(
        "bubble-sort", 0.95, "array-sort", evidence, roles,
        range.begin.line, range.end.line, AstAnalyzer.nodeId(outer),
        AstAnalyzer.nodeId(method), ids, sort));
  }

  private static List<Statement> statements(Statement statement) {
    return statement instanceof BlockStmt block ? block.getStatements() : List.of(statement);
  }

  private static boolean zero(Expression expression) {
    return SortSemanticFacts.unwrap(expression) instanceof IntegerLiteralExpr literal
        && literal.getValue().equals("0");
  }

  private static boolean harmlessScalar(Statement statement, SortSemanticFacts.Counter pass,
      SortSemanticFacts.Counter scan, SortSemanticFacts.Analysis facts) {
    if (!(statement instanceof ExpressionStmt expression)
        || !(expression.getExpression() instanceof VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return false;
    VariableDeclarator variable = declaration.getVariable(0);
    if (!variable.getType().asString().equals("int") || variable.getInitializer().isEmpty())
      return false;
    Expression value = SortSemanticFacts.unwrap(variable.getInitializer().get());
    if (value instanceof IntegerLiteralExpr) return true;
    var operand = facts.indexOperand(value);
    return operand.isPresent() && operand.get().offset() == 0
        && (operand.get().declarationId().equals(pass.declarationId())
            || operand.get().declarationId().equals(scan.declarationId()));
  }

  private static VariableDeclarator falseFlag(Statement statement) {
    if (!(statement instanceof ExpressionStmt expression)
        || !(expression.getExpression() instanceof VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return null;
    VariableDeclarator flag = declaration.getVariable(0);
    return flag.getType().asString().equals("boolean")
        && flag.getInitializer().map(SortSemanticFacts::unwrap)
            .filter(BooleanLiteralExpr.class::isInstance)
            .map(BooleanLiteralExpr.class::cast)
            .filter(value -> !value.getValue()).isPresent() ? flag : null;
  }

  private static boolean breakOnFalse(Statement statement, String flagId) {
    if (!(statement instanceof IfStmt branch) || branch.getElseStmt().isPresent()) return false;
    Expression condition = SortSemanticFacts.unwrap(branch.getCondition());
    if (!(condition instanceof UnaryExpr negation)
        || negation.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
        || !(SortSemanticFacts.unwrap(negation.getExpression()) instanceof NameExpr name)
        || !LocalVariableBindings.resolve(name).filter(flagId::equals).isPresent()) return false;
    List<Statement> body = statements(branch.getThenStmt());
    return body.size() == 1 && body.get(0) instanceof BreakStmt end && end.getLabel().isEmpty();
  }

  private record Element(SortSemanticFacts.SequenceBinding binding,
      SortSemanticFacts.IndexOperand index) {}

  private static Optional<Element> element(Expression expression, SortSemanticFacts.Analysis facts) {
    Expression value = SortSemanticFacts.unwrap(expression);
    if (!(value instanceof ArrayAccessExpr access)) return Optional.empty();
    var binding = facts.sequenceAccess(access);
    var index = facts.indexOperand(access.getIndex());
    return binding.isPresent() && index.isPresent()
        ? Optional.of(new Element(binding.get().binding(), index.get())) : Optional.empty();
  }

  private static boolean at(Expression expression, SortSemanticFacts.SequenceBinding sequence,
      SortSemanticFacts.Counter scan, int offset, SortSemanticFacts.Analysis facts) {
    var element = element(expression, facts);
    return element.isPresent()
        && element.get().binding().canonicalDeclarationId().equals(sequence.canonicalDeclarationId())
        && element.get().index().declarationId().equals(scan.declarationId())
        && element.get().index().offset() == offset;
  }

  private static VariableDeclarator directSwap(List<Statement> body,
      SortSemanticFacts.SequenceBinding sequence, SortSemanticFacts.Counter scan,
      SortSemanticFacts.Analysis facts, VariableDeclarator flag) {
    if (body.size() != (flag == null ? 3 : 4)
        || !(body.get(0) instanceof ExpressionStmt first)
        || !(first.getExpression() instanceof VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return null;
    VariableDeclarator temp = declaration.getVariable(0);
    if (!temp.getType().asString().equals(sequence.elementType()) || temp.getInitializer().isEmpty()
        || !at(temp.getInitializer().get(), sequence, scan, 0, facts)) return null;
    AssignExpr writeFirst = assignment(body.get(1));
    AssignExpr writeSecond = assignment(body.get(2));
    if (writeFirst == null || writeSecond == null
        || !at(writeFirst.getTarget(), sequence, scan, 0, facts)
        || !at(writeFirst.getValue(), sequence, scan, 1, facts)
        || !at(writeSecond.getTarget(), sequence, scan, 1, facts)
        || !(SortSemanticFacts.unwrap(writeSecond.getValue()) instanceof NameExpr restored)
        || !LocalVariableBindings.resolve(restored).filter(AstAnalyzer.nodeId(temp)::equals).isPresent())
      return null;
    if (flag != null) {
      AssignExpr flagWrite = assignment(body.get(3));
      if (flagWrite == null
          || !(SortSemanticFacts.unwrap(flagWrite.getTarget()) instanceof NameExpr name)
          || !LocalVariableBindings.resolve(name).filter(AstAnalyzer.nodeId(flag)::equals).isPresent()
          || !(SortSemanticFacts.unwrap(flagWrite.getValue()) instanceof BooleanLiteralExpr value)
          || !value.getValue()) return null;
    }
    return temp;
  }

  private static AssignExpr assignment(Statement statement) {
    return statement instanceof ExpressionStmt expression
        && expression.getExpression() instanceof AssignExpr assign
        && assign.getOperator() == AssignExpr.Operator.ASSIGN ? assign : null;
  }

  private static boolean setsFlag(Statement statement, VariableDeclarator flag) {
    AssignExpr write = assignment(statement);
    if (write == null
        || !(SortSemanticFacts.unwrap(write.getTarget()) instanceof NameExpr name)
        || !LocalVariableBindings.resolve(name).filter(AstAnalyzer.nodeId(flag)::equals).isPresent()
        || !(SortSemanticFacts.unwrap(write.getValue()) instanceof BooleanLiteralExpr value)) return false;
    return value.getValue();
  }

  private static boolean sameExchange(SameFileHelperSummaries.Exchange exchange,
      SortSemanticFacts.SequenceBinding sequence, SortSemanticFacts.Counter scan) {
    return exchange.sequence().canonicalDeclarationId().equals(sequence.canonicalDeclarationId())
        && exchange.firstIndex().declarationId().equals(scan.declarationId())
        && exchange.secondIndex().declarationId().equals(scan.declarationId())
        && ((exchange.firstIndex().offset() == 0 && exchange.secondIndex().offset() == 1)
            || (exchange.firstIndex().offset() == 1 && exchange.secondIndex().offset() == 0));
  }
}
