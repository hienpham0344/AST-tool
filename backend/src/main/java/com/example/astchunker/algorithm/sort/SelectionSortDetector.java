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
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

  @Override
  public Optional<AlgorithmHint> detect(Node node, SortPatternRegistry.Session session) {
    if (!(node instanceof ForStmt outer)) return Optional.empty();
    var facts = session.facts();
    var pass = facts.forwardUnitCounter(outer);
    if (pass.isEmpty() || !zero(pass.get().initializer())) return Optional.empty();

    List<Statement> outerStatements = statements(outer.getBody());
    if (outerStatements.size() != 3 && outerStatements.size() != 5
        || !(outerStatements.get(0) instanceof ExpressionStmt selectedStatement)
        || !(selectedStatement.getExpression() instanceof VariableDeclarationExpr selectedDeclaration)
        || selectedDeclaration.getVariables().size() != 1
        || !(outerStatements.get(1) instanceof ForStmt inner)) return Optional.empty();
    VariableDeclarator selected = selectedDeclaration.getVariable(0);
    if (!selected.getType().asString().equals("int") || selected.getInitializer().isEmpty()
        || !isCounter(selected.getInitializer().get(), pass.get(), facts)) return Optional.empty();
    String selectedId = AstAnalyzer.nodeId(selected);

    var scan = facts.forwardUnitCounter(inner);
    if (scan.isEmpty() || !startsAfter(scan.get(), pass.get(), facts)) return Optional.empty();
    IfStmt comparisonBranch = comparisonBranch(inner, pass.get(), scan.get(), facts);
    if (comparisonBranch == null || comparisonBranch.getElseStmt().isPresent()) return Optional.empty();
    AssignExpr selectionUpdate = selectedUpdate(comparisonBranch, selectedId, scan.get());
    if (selectionUpdate == null || countWrites(outer, selectedId) != 1) return Optional.empty();

    var comparison = facts.strictComparison(comparisonBranch.getCondition());
    if (comparison.isEmpty()) return Optional.empty();
    Element left = element(comparison.get().left(), facts);
    Element right = element(comparison.get().right(), facts);
    if (left == null || right == null
        || left.binding().kind() != SortSemanticFacts.SequenceKind.INT_ARRAY
        || !left.binding().canonicalDeclarationId().equals(right.binding().canonicalDeclarationId()))
      return Optional.empty();
    var sequence = left.binding();
    var outerBound = facts.supportedLengthBound(outer, sequence);
    var innerBound = facts.supportedLengthBound(inner, sequence);
    if (outerBound.isEmpty() || outerBound.get().lengthOffset() != -1 || outerBound.get().pass().isPresent()
        || innerBound.isEmpty() || innerBound.get().lengthOffset() != 0 || innerBound.get().pass().isPresent())
      return Optional.empty();

    SortPattern.Direction direction;
    if (left.index().declarationId().equals(selectedId) && left.index().offset() == 0
        && right.index().declarationId().equals(scan.get().declarationId()) && right.index().offset() == 0)
      direction = SortPattern.Direction.ASCENDING;
    else if (left.index().declarationId().equals(scan.get().declarationId()) && left.index().offset() == 0
        && right.index().declarationId().equals(selectedId) && right.index().offset() == 0)
      direction = SortPattern.Direction.DESCENDING;
    else return Optional.empty();

    Placement placement = placement(outerStatements.subList(2, outerStatements.size()), sequence,
        pass.get(), selectedId, facts, session);
    if (placement == null) return Optional.empty();
    Node method = LocalVariableBindings.callable(outer);
    var range = outer.getRange().orElse(null);
    if (method == null || range == null) return Optional.empty();

    Map<String, String> roles = new LinkedHashMap<>();
    Map<String, String> ids = new LinkedHashMap<>();
    roles.put("array", sequence.displayName());
    roles.put("pass", pass.get().displayName());
    roles.put("scan", scan.get().displayName());
    roles.put("selected", selected.getNameAsString());
    ids.put("array", sequence.sourceDeclarationId());
    ids.put("pass", pass.get().declarationId());
    ids.put("scan", scan.get().declarationId());
    ids.put("selected", selectedId);
    if (placement.temporary() != null) {
      roles.put("temp", placement.temporary().getNameAsString());
      ids.put("temp", AstAnalyzer.nodeId(placement.temporary()));
    }
    boolean representableSwap = placement.directStatements() != null
        && at(placement.temporary().getInitializer().orElseThrow(), sequence,
            pass.get().declarationId(), facts);
    SortPattern sort = !representableSwap ? null : new SortPattern(
        1, direction, AstAnalyzer.nodeId(inner), AstAnalyzer.nodeId(SortSemanticFacts.unwrap(comparisonBranch.getCondition())),
        AstAnalyzer.nodeId(comparisonBranch), placement.directStatements().stream().map(AstAnalyzer::nodeId).toList(),
        new SortPattern.Operand(scan.get().declarationId(), 0), new SortPattern.Operand(selectedId, 0),
        Map.of("selectedIndexUpdate", AstAnalyzer.nodeId(statements(comparisonBranch.getThenStmt()).get(0))));
    List<String> evidence = new ArrayList<>(List.of(
        "A pass-bound selected index is updated only by a strict same-array comparison",
        "A complete remaining-region scan precedes one boundary placement"));
    if (placement.helper() != null)
      evidence.add("Resolved same-file exchange helper " + placement.helper().helperCallableId()
          + " at " + placement.helper().callSiteId());
    else evidence.add("Three caller statements prove the boundary exchange");
    return Optional.of(new AlgorithmHint("selection-sort", 0.96, "array-sort", evidence, roles,
        range.begin.line, range.end.line, AstAnalyzer.nodeId(outer), AstAnalyzer.nodeId(method), ids, sort));
  }

  private static List<Statement> statements(Statement statement) {
    return statement instanceof BlockStmt block ? block.getStatements() : List.of(statement);
  }

  private static boolean zero(Expression expression) {
    return SortSemanticFacts.unwrap(expression) instanceof IntegerLiteralExpr literal
        && literal.getValue().equals("0");
  }

  private static boolean isCounter(Expression expression, SortSemanticFacts.Counter counter,
      SortSemanticFacts.Analysis facts) {
    return facts.indexOperand(expression).filter(index -> index.offset() == 0
        && index.declarationId().equals(counter.declarationId())).isPresent();
  }

  private static boolean startsAfter(SortSemanticFacts.Counter scan, SortSemanticFacts.Counter pass,
      SortSemanticFacts.Analysis facts) {
    return facts.indexOperand(scan.initializer()).filter(index -> index.offset() == 1
        && index.declarationId().equals(pass.declarationId())).isPresent();
  }

  private static IfStmt comparisonBranch(ForStmt inner, SortSemanticFacts.Counter pass,
      SortSemanticFacts.Counter scan, SortSemanticFacts.Analysis facts) {
    IfStmt branch = null;
    for (Statement statement : statements(inner.getBody())) {
      if (statement instanceof IfStmt candidate) {
        if (branch != null) return null;
        branch = candidate;
      } else if (!harmlessScalar(statement, pass, scan, facts)) return null;
    }
    return branch;
  }

  private static boolean harmlessScalar(Statement statement, SortSemanticFacts.Counter pass,
      SortSemanticFacts.Counter scan, SortSemanticFacts.Analysis facts) {
    if (!(statement instanceof ExpressionStmt expression)
        || !(expression.getExpression() instanceof VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return false;
    VariableDeclarator local = declaration.getVariable(0);
    if (!local.getType().asString().equals("int") || local.getInitializer().isEmpty()) return false;
    Expression value = SortSemanticFacts.unwrap(local.getInitializer().get());
    if (value instanceof IntegerLiteralExpr) return true;
    return facts.indexOperand(value).filter(index -> index.offset() == 0
        && (index.declarationId().equals(pass.declarationId()) || index.declarationId().equals(scan.declarationId())))
        .isPresent();
  }

  private static AssignExpr selectedUpdate(IfStmt branch, String selectedId,
      SortSemanticFacts.Counter scan) {
    List<Statement> body = statements(branch.getThenStmt());
    if (body.size() != 1 || !(body.get(0) instanceof ExpressionStmt expression)
        || !(expression.getExpression() instanceof AssignExpr assign)
        || assign.getOperator() != AssignExpr.Operator.ASSIGN
        || !(SortSemanticFacts.unwrap(assign.getTarget()) instanceof NameExpr target)
        || !LocalVariableBindings.resolve(target).filter(selectedId::equals).isPresent()
        || !(SortSemanticFacts.unwrap(assign.getValue()) instanceof NameExpr value)
        || !LocalVariableBindings.resolve(value).filter(scan.declarationId()::equals).isPresent()) return null;
    return assign;
  }

  private static long countWrites(Node scope, String declarationId) {
    long assignments = scope.findAll(AssignExpr.class).stream().filter(assign ->
        SortSemanticFacts.unwrap(assign.getTarget()) instanceof NameExpr name
            && LocalVariableBindings.resolve(name).filter(declarationId::equals).isPresent()).count();
    long updates = scope.findAll(UnaryExpr.class).stream().filter(update ->
        (update.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT || update.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
            || update.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT || update.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT)
            && SortSemanticFacts.unwrap(update.getExpression()) instanceof NameExpr name
            && LocalVariableBindings.resolve(name).filter(declarationId::equals).isPresent()).count();
    return assignments + updates;
  }

  private record Element(SortSemanticFacts.SequenceBinding binding, SortSemanticFacts.IndexOperand index) {}

  private static Element element(Expression expression, SortSemanticFacts.Analysis facts) {
    Expression value = SortSemanticFacts.unwrap(expression);
    if (!(value instanceof ArrayAccessExpr access)) return null;
    var receiver = facts.sequenceAccess(access);
    var index = facts.indexOperand(access.getIndex());
    return receiver.isPresent() && index.isPresent() ? new Element(receiver.get().binding(), index.get()) : null;
  }

  private record Placement(VariableDeclarator temporary, List<Statement> directStatements,
      SameFileHelperSummaries.Exchange helper) {}

  private static Placement placement(List<Statement> statements, SortSemanticFacts.SequenceBinding sequence,
      SortSemanticFacts.Counter pass, String selectedId, SortSemanticFacts.Analysis facts,
      SortPatternRegistry.Session session) {
    if (statements.size() == 3) {
      VariableDeclarator temporary = directExchange(statements, sequence, pass, selectedId, facts);
      return temporary == null ? null : new Placement(temporary, statements, null);
    }
    if (statements.size() == 1 && statements.get(0) instanceof ExpressionStmt)
      return helperPlacement(statements.get(0), sequence, pass, selectedId, session);
    if (statements.size() != 1 || !(statements.get(0) instanceof IfStmt guard)
        || guard.getElseStmt().isPresent() || !different(guard.getCondition(), selectedId, pass.declarationId())) return null;
    List<Statement> body = statements(guard.getThenStmt());
    VariableDeclarator temporary = directExchange(body, sequence, pass, selectedId, facts);
    if (temporary != null) return new Placement(temporary, body, null);
    return body.size() == 1
        ? helperPlacement(body.get(0), sequence, pass, selectedId, session) : null;
  }

  private static Placement helperPlacement(Statement statement,
      SortSemanticFacts.SequenceBinding sequence, SortSemanticFacts.Counter pass,
      String selectedId, SortPatternRegistry.Session session) {
    if (!(statement instanceof ExpressionStmt expression)
        || !(expression.getExpression() instanceof MethodCallExpr call)) return null;
    var helper = session.helpers().resolveExchange(call).value();
    if (helper.isEmpty() || !samePlacement(helper.get(), sequence, pass, selectedId)) return null;
    return new Placement(null, null, helper.get());
  }

  private static boolean different(Expression expression, String selectedId, String passId) {
    Expression value = SortSemanticFacts.unwrap(expression);
    if (!(value instanceof BinaryExpr binary) || binary.getOperator() != BinaryExpr.Operator.NOT_EQUALS) return false;
    return resolves(binary.getLeft(), selectedId) && resolves(binary.getRight(), passId)
        || resolves(binary.getLeft(), passId) && resolves(binary.getRight(), selectedId);
  }

  private static boolean resolves(Expression expression, String id) {
    return SortSemanticFacts.unwrap(expression) instanceof NameExpr name
        && LocalVariableBindings.resolve(name).filter(id::equals).isPresent();
  }

  private static VariableDeclarator directExchange(List<Statement> body,
      SortSemanticFacts.SequenceBinding sequence, SortSemanticFacts.Counter pass, String selectedId,
      SortSemanticFacts.Analysis facts) {
    if (body.size() != 3 || !(body.get(0) instanceof ExpressionStmt expression)
        || !(expression.getExpression() instanceof VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return null;
    VariableDeclarator temporary = declaration.getVariable(0);
    if (!temporary.getType().asString().equals("int") || temporary.getInitializer().isEmpty()) return null;
    AssignExpr first = assignment(body.get(1)), second = assignment(body.get(2));
    if (first == null || second == null
        || !(SortSemanticFacts.unwrap(second.getValue()) instanceof NameExpr restored)
        || !LocalVariableBindings.resolve(restored).filter(AstAnalyzer.nodeId(temporary)::equals).isPresent()) return null;
    boolean passFirst = at(temporary.getInitializer().get(), sequence, pass.declarationId(), facts)
        && at(first.getTarget(), sequence, pass.declarationId(), facts)
        && at(first.getValue(), sequence, selectedId, facts)
        && at(second.getTarget(), sequence, selectedId, facts);
    boolean selectedFirst = at(temporary.getInitializer().get(), sequence, selectedId, facts)
        && at(first.getTarget(), sequence, selectedId, facts)
        && at(first.getValue(), sequence, pass.declarationId(), facts)
        && at(second.getTarget(), sequence, pass.declarationId(), facts);
    if (!passFirst && !selectedFirst) return null;
    return temporary;
  }

  private static AssignExpr assignment(Statement statement) {
    return statement instanceof ExpressionStmt expression && expression.getExpression() instanceof AssignExpr assign
        && assign.getOperator() == AssignExpr.Operator.ASSIGN ? assign : null;
  }

  private static boolean at(Expression expression, SortSemanticFacts.SequenceBinding sequence,
      String indexId, SortSemanticFacts.Analysis facts) {
    Element element = element(expression, facts);
    return element != null && element.binding().canonicalDeclarationId().equals(sequence.canonicalDeclarationId())
        && element.index().offset() == 0 && element.index().declarationId().equals(indexId);
  }

  private static boolean samePlacement(SameFileHelperSummaries.Exchange helper,
      SortSemanticFacts.SequenceBinding sequence, SortSemanticFacts.Counter pass, String selectedId) {
    if (!helper.sequence().canonicalDeclarationId().equals(sequence.canonicalDeclarationId())) return false;
    String first = helper.firstIndex().declarationId(), second = helper.secondIndex().declarationId();
    return helper.firstIndex().offset() == 0 && helper.secondIndex().offset() == 0
        && (first.equals(pass.declarationId()) && second.equals(selectedId)
            || first.equals(selectedId) && second.equals(pass.declarationId()));
  }
}
