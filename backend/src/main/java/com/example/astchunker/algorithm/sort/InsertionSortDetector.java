package com.example.astchunker.algorithm.sort;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SortPattern;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

  @Override
  public Optional<AlgorithmHint> detect(Node node, SortPatternRegistry.Session session) {
    if (!(node instanceof ForStmt outer)) return Optional.empty();
    var facts = session.facts();
    var pass = facts.forwardUnitCounter(outer);
    if (pass.isEmpty() || !one(pass.get().initializer())) return Optional.empty();

    List<Statement> body = statements(outer.getBody());
    Optional<AlgorithmHint> adjacent = adjacentExchange(outer, body, pass.get(), facts, session);
    if (adjacent.isPresent()) return adjacent;
    if (body.size() < 4) return Optional.empty();
    Key key = key(body.get(0), pass.get(), facts);
    if (key == null) return Optional.empty();
    var outerBound = facts.supportedLengthBound(outer, key.sequence());
    if (outerBound.isEmpty() || outerBound.get().lengthOffset() != 0 || outerBound.get().pass().isPresent())
      return Optional.empty();
    Scan scan = scan(body.get(1), pass.get(), facts);
    if (scan == null || scan.counter().declarationId().equals(pass.get().declarationId())) return Optional.empty();
    if (!(body.get(2) instanceof WhileStmt shifting)) return Optional.empty();
    SemanticCondition condition = shiftCondition(shifting.getCondition(), scan.counter(), key, facts, session);
    if (condition == null) return Optional.empty();
    Movement movement = movement(shifting.getBody(), key.sequence(), scan.counter(), facts);
    if (movement == null || !insertion(body.subList(3, body.size()), key, scan.counter(), facts)) return Optional.empty();

    Node method = LocalVariableBindings.callable(outer);
    var range = outer.getRange().orElse(null);
    if (method == null || range == null) return Optional.empty();
    Map<String, String> roles = new LinkedHashMap<>();
    Map<String, String> ids = new LinkedHashMap<>();
    roles.put("array", key.sequence().displayName());
    roles.put("pass", pass.get().displayName());
    roles.put("scan", scan.counter().displayName());
    roles.put("key", key.variable().getNameAsString());
    ids.put("array", key.sequence().sourceDeclarationId());
    ids.put("pass", pass.get().declarationId());
    ids.put("scan", scan.counter().declarationId());
    ids.put("key", AstAnalyzer.nodeId(key.variable()));
    if (condition.comparator() != null) {
      roles.put("comparator", condition.comparator().displayName());
      ids.put("comparator", condition.comparator().declarationId());
    }
    SortPattern sort = condition.helperComparison() != null
            || key.sequence().kind() != SortSemanticFacts.SequenceKind.INT_ARRAY
        ? null : new SortPattern(
        1, condition.direction(), AstAnalyzer.nodeId(shifting), condition.comparisonId(),
        AstAnalyzer.nodeId(shifting), List.of(),
        new SortPattern.Operand(scan.counter().declarationId(), 0),
        new SortPattern.Operand(pass.get().declarationId(), 0),
        Map.of("shiftWrite", AstAnalyzer.nodeId(movement.shiftWrite()),
            "scanDecrement", AstAnalyzer.nodeId(movement.decrement()),
            "insertWrite", AstAnalyzer.nodeId(body.get(3))));
    List<String> evidence = new ArrayList<>(List.of(
        condition.comparator() == null
            ? "A forward pass retains one key from a single numeric primitive array receiver"
            : "A forward pass retains one key from a comparator-bound reference array receiver",
        "A boundary-first backward scan shifts adjacent values before inserting the key"));
    if (condition.comparator() != null) {
      evidence.add("A direct fully-qualified Comparator parameter compares the scan element and saved key");
    } else if (condition.helperComparison() != null) {
      evidence.add("Resolved same-file strict comparison helper "
          + condition.helperComparison().helperCallableId() + " at " + condition.helperComparison().callSiteId());
    }
    else evidence.add("A direct strict comparison proves the shift direction");
    return Optional.of(new AlgorithmHint("insertion-sort", 0.96, "array-sort", evidence, roles,
        range.begin.line, range.end.line, AstAnalyzer.nodeId(outer), AstAnalyzer.nodeId(method), ids, sort));
  }

  private static Optional<AlgorithmHint> adjacentExchange(ForStmt outer, List<Statement> body,
      SortSemanticFacts.Counter pass, SortSemanticFacts.Analysis facts, SortPatternRegistry.Session session) {
    if (body.size() != 1 || !(body.get(0) instanceof ForStmt inner)) return Optional.empty();
    var scan = facts.backwardUnitCounter(inner);
    if (scan.isEmpty() || !facts.indexOperand(scan.get().initializer()).filter(index -> index.offset() == 0
        && index.declarationId().equals(pass.declarationId())).isPresent()) return Optional.empty();
    Expression condition = SortSemanticFacts.unwrap(inner.getCompare().orElse(null));
    if (!(condition instanceof BinaryExpr conjunction) || conjunction.getOperator() != BinaryExpr.Operator.AND
        || !positiveBoundary(conjunction.getLeft(), scan.get())) return Optional.empty();
    Expression comparison = SortSemanticFacts.unwrap(conjunction.getRight());
    if (!(comparison instanceof BinaryExpr binary)
        || (binary.getOperator() != BinaryExpr.Operator.LESS && binary.getOperator() != BinaryExpr.Operator.GREATER))
      return Optional.empty();
    Element left = element(binary.getLeft(), facts), right = element(binary.getRight(), facts);
    if (left == null || right == null || !left.binding().isNumericPrimitiveArray()
        || !left.binding().canonicalDeclarationId().equals(right.binding().canonicalDeclarationId())
        || !left.index().declarationId().equals(scan.get().declarationId())
        || !right.index().declarationId().equals(scan.get().declarationId())) return Optional.empty();
    SortPattern.Direction direction;
    if (left.index().offset() == 0 && right.index().offset() == -1)
      direction = binary.getOperator() == BinaryExpr.Operator.LESS ? SortPattern.Direction.ASCENDING : SortPattern.Direction.DESCENDING;
    else if (left.index().offset() == -1 && right.index().offset() == 0)
      direction = binary.getOperator() == BinaryExpr.Operator.GREATER ? SortPattern.Direction.ASCENDING : SortPattern.Direction.DESCENDING;
    else return Optional.empty();
    var outerBound = facts.supportedLengthBound(outer, left.binding());
    if (outerBound.isEmpty() || outerBound.get().lengthOffset() != 0 || outerBound.get().pass().isPresent()) return Optional.empty();
    List<Statement> exchangeStatements = statements(inner.getBody());
    VariableDeclarator temp = adjacentDirectExchange(exchangeStatements, left.binding(), scan.get(), facts);
    SameFileHelperSummaries.Exchange helper = null;
    if (temp == null && exchangeStatements.size() == 1
        && exchangeStatements.get(0) instanceof ExpressionStmt expression
        && expression.getExpression() instanceof MethodCallExpr call) {
      helper = session.helpers().resolveExchange(call).value().orElse(null);
      if (helper == null || !adjacentHelper(helper, left.binding(), scan.get())) return Optional.empty();
    }
    if (temp == null && helper == null) return Optional.empty();
    Node method = LocalVariableBindings.callable(outer);
    var range = outer.getRange().orElse(null);
    if (method == null || range == null) return Optional.empty();
    Map<String, String> roles = new LinkedHashMap<>();
    Map<String, String> ids = new LinkedHashMap<>();
    roles.put("array", left.binding().displayName()); roles.put("pass", pass.displayName());
    roles.put("scan", scan.get().displayName());
    if (temp != null) roles.put("temp", temp.getNameAsString());
    ids.put("array", left.binding().sourceDeclarationId()); ids.put("pass", pass.declarationId());
    ids.put("scan", scan.get().declarationId());
    if (temp != null) ids.put("temp", AstAnalyzer.nodeId(temp));
    List<String> evidence = new ArrayList<>(List.of(
        "A forward pass drives a backward adjacent exchange over one numeric primitive array receiver",
        "The adjacent exchange is static-only because it has no insertion trace metadata"));
    if (helper != null) evidence.add("Resolved same-file exchange helper "
        + helper.helperCallableId() + " at " + helper.callSiteId());
    return Optional.of(new AlgorithmHint("insertion-sort", 0.96, "array-sort", evidence, roles,
        range.begin.line, range.end.line, AstAnalyzer.nodeId(outer), AstAnalyzer.nodeId(method), ids, null));
  }

  private static boolean positiveBoundary(Expression expression, SortSemanticFacts.Counter scan) {
    Expression value = SortSemanticFacts.unwrap(expression);
    return value instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.GREATER
        && resolves(binary.getLeft(), scan.declarationId())
        && SortSemanticFacts.unwrap(binary.getRight()) instanceof IntegerLiteralExpr literal
        && literal.getValue().equals("0");
  }

  private static VariableDeclarator adjacentDirectExchange(List<Statement> statements,
      SortSemanticFacts.SequenceBinding sequence, SortSemanticFacts.Counter scan,
      SortSemanticFacts.Analysis facts) {
    if (statements.size() != 3 || !(statements.get(0) instanceof ExpressionStmt first)
        || !(first.getExpression() instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return null;
    VariableDeclarator temp = declaration.getVariable(0);
    if (!temp.getType().asString().equals(sequence.elementType()) || temp.getInitializer().isEmpty()) return null;
    AssignExpr firstWrite = assignment(statements.get(1)), secondWrite = assignment(statements.get(2));
    if (firstWrite == null || secondWrite == null) return null;
    boolean normal = arrayAt(temp.getInitializer().get(), sequence, scan, 0, facts)
        && arrayAt(firstWrite.getTarget(), sequence, scan, 0, facts)
        && arrayAt(firstWrite.getValue(), sequence, scan, -1, facts)
        && arrayAt(secondWrite.getTarget(), sequence, scan, -1, facts)
        && keyOperand(secondWrite.getValue(), temp);
    boolean reversed = arrayAt(temp.getInitializer().get(), sequence, scan, -1, facts)
        && arrayAt(firstWrite.getTarget(), sequence, scan, -1, facts)
        && arrayAt(firstWrite.getValue(), sequence, scan, 0, facts)
        && arrayAt(secondWrite.getTarget(), sequence, scan, 0, facts)
        && keyOperand(secondWrite.getValue(), temp);
    if (!normal && !reversed) return null;
    return temp;
  }

  private static boolean adjacentHelper(SameFileHelperSummaries.Exchange helper,
      SortSemanticFacts.SequenceBinding sequence, SortSemanticFacts.Counter scan) {
    if (!helper.sequence().canonicalDeclarationId().equals(sequence.canonicalDeclarationId())
        || !helper.firstIndex().declarationId().equals(scan.declarationId())
        || !helper.secondIndex().declarationId().equals(scan.declarationId())) return false;
    return helper.firstIndex().offset() == 0 && helper.secondIndex().offset() == -1
        || helper.firstIndex().offset() == -1 && helper.secondIndex().offset() == 0;
  }

  private static List<Statement> statements(Statement statement) {
    return statement instanceof BlockStmt block ? block.getStatements() : List.of(statement);
  }

  private static boolean one(Expression expression) {
    return SortSemanticFacts.unwrap(expression) instanceof IntegerLiteralExpr literal
        && literal.getValue().equals("1");
  }

  private static Key key(Statement statement, SortSemanticFacts.Counter pass,
      SortSemanticFacts.Analysis facts) {
    if (!(statement instanceof ExpressionStmt expression)
        || !(expression.getExpression() instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return null;
    VariableDeclarator variable = declaration.getVariable(0);
    if (variable.getInitializer().isEmpty()
        || !(SortSemanticFacts.unwrap(variable.getInitializer().get()) instanceof ArrayAccessExpr access)) return null;
    var sequence = facts.sequenceAccess(access);
    var index = facts.indexOperand(access.getIndex());
    if (sequence.isEmpty() || index.isEmpty() || index.get().offset() != 0
        || !index.get().declarationId().equals(pass.declarationId())) return null;
    var binding = sequence.get().binding();
    if ((!binding.isNumericPrimitiveArray() && binding.kind() != SortSemanticFacts.SequenceKind.OBJECT_ARRAY)
        || !variable.getType().asString().equals(binding.elementType()))
      return null;
    return new Key(variable, binding);
  }

  private static Scan scan(Statement statement, SortSemanticFacts.Counter pass,
      SortSemanticFacts.Analysis facts) {
    if (!(statement instanceof ExpressionStmt expression)
        || !(expression.getExpression() instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return null;
    VariableDeclarator variable = declaration.getVariable(0);
    if (!variable.getType().asString().equals("int") || variable.getInitializer().isEmpty()) return null;
    var initializer = facts.indexOperand(variable.getInitializer().get());
    if (initializer.isEmpty() || initializer.get().offset() != -1
        || !initializer.get().declarationId().equals(pass.declarationId())) return null;
    return new Scan(new SortSemanticFacts.Counter(AstAnalyzer.nodeId(variable), AstAnalyzer.nodeId(variable),
        variable.getNameAsString(), -1, variable.getInitializer().get(), ""));
  }

  private static SemanticCondition shiftCondition(Expression expression, SortSemanticFacts.Counter scan, Key key,
      SortSemanticFacts.Analysis facts, SortPatternRegistry.Session session) {
    Expression value = SortSemanticFacts.unwrap(expression);
    if (!(value instanceof BinaryExpr conjunction) || conjunction.getOperator() != BinaryExpr.Operator.AND
        || !boundary(conjunction.getLeft(), scan)) return null;
    Expression comparison = SortSemanticFacts.unwrap(conjunction.getRight());
    if (comparison instanceof MethodCallExpr call) {
      if (!key.sequence().isNumericPrimitiveArray()) return null;
      var resolved = session.helpers().resolveComparison(call).value();
      if (resolved.isEmpty()) return null;
      var helper = resolved.get();
      boolean arrayLeft = comparisonOperand(helper.left(), key.sequence(), scan, 0)
          && keyOperand(helper.right(), key.variable(), call);
      boolean keyLeft = keyOperand(helper.left(), key.variable(), call)
          && comparisonOperand(helper.right(), key.sequence(), scan, 0);
      if (!arrayLeft && !keyLeft) return null;
      return new SemanticCondition(arrayLeft ? SortPattern.Direction.ASCENDING : SortPattern.Direction.DESCENDING,
          AstAnalyzer.nodeId(call), helper, null);
    }
    var comparatorComparison = facts.strictComparatorComparison(comparison);
    if (comparatorComparison.isPresent()) {
      var normalized = comparatorComparison.get();
      if (!normalized.comparator().supports(key.sequence())) return null;
      boolean arrayLeft = arrayAt(normalized.left(), key.sequence(), scan, 0, facts)
          && keyOperand(normalized.right(), key.variable());
      boolean keyLeft = keyOperand(normalized.left(), key.variable())
          && arrayAt(normalized.right(), key.sequence(), scan, 0, facts);
      if (!arrayLeft && !keyLeft) return null;
      boolean ascending = arrayLeft
          ? normalized.operator() == BinaryExpr.Operator.GREATER
          : normalized.operator() == BinaryExpr.Operator.LESS;
      return new SemanticCondition(
          ascending ? SortPattern.Direction.ASCENDING : SortPattern.Direction.DESCENDING,
          normalized.astNodeId(), null, normalized.comparator());
    }
    if (!key.sequence().isNumericPrimitiveArray()) return null;
    if (!(comparison instanceof BinaryExpr binary)
        || (binary.getOperator() != BinaryExpr.Operator.GREATER && binary.getOperator() != BinaryExpr.Operator.LESS)) return null;
    boolean arrayLeft = arrayAt(binary.getLeft(), key.sequence(), scan, 0, facts)
        && keyOperand(binary.getRight(), key.variable());
    boolean keyLeft = keyOperand(binary.getLeft(), key.variable())
        && arrayAt(binary.getRight(), key.sequence(), scan, 0, facts);
    if (!arrayLeft && !keyLeft) return null;
    boolean ascending = arrayLeft ? binary.getOperator() == BinaryExpr.Operator.GREATER
        : binary.getOperator() == BinaryExpr.Operator.LESS;
    return new SemanticCondition(ascending ? SortPattern.Direction.ASCENDING : SortPattern.Direction.DESCENDING,
        AstAnalyzer.nodeId(binary), null, null);
  }

  private static boolean boundary(Expression expression, SortSemanticFacts.Counter scan) {
    Expression value = SortSemanticFacts.unwrap(expression);
    return value instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.GREATER_EQUALS
        && resolves(binary.getLeft(), scan.declarationId())
        && SortSemanticFacts.unwrap(binary.getRight()) instanceof IntegerLiteralExpr literal
        && literal.getValue().equals("0");
  }

  private static Movement movement(Statement statement, SortSemanticFacts.SequenceBinding sequence,
      SortSemanticFacts.Counter scan, SortSemanticFacts.Analysis facts) {
    Statement write = null, decrement = null;
    for (Statement candidate : statements(statement)) {
      if (isHarmlessScalar(candidate, scan, facts)) {
        if (write == null || decrement != null) return null;
        continue;
      }
      AssignExpr assignment = assignment(candidate);
      if (assignment != null && arrayAt(assignment.getTarget(), sequence, scan, 1, facts)
          && arrayAt(assignment.getValue(), sequence, scan, 0, facts) && write == null) { write = candidate; continue; }
      if (decrement(candidate, scan) && decrement == null && write != null) { decrement = candidate; continue; }
      return null;
    }
    return write != null && decrement != null ? new Movement(write, decrement) : null;
  }

  private static boolean insertion(List<Statement> candidates, Key key, SortSemanticFacts.Counter scan,
      SortSemanticFacts.Analysis facts) {
    if (candidates.size() != 1) return false;
    AssignExpr assignment = assignment(candidates.get(0));
    return assignment != null && arrayAt(assignment.getTarget(), key.sequence(), scan, 1, facts)
        && keyOperand(assignment.getValue(), key.variable());
  }

  private static boolean isHarmlessScalar(Statement statement, SortSemanticFacts.Counter scan,
      SortSemanticFacts.Analysis facts) {
    if (!(statement instanceof ExpressionStmt expression)
        || !(expression.getExpression() instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr declaration)
        || declaration.getVariables().size() != 1) return false;
    VariableDeclarator local = declaration.getVariable(0);
    return local.getType().asString().equals("int") && local.getInitializer().isPresent()
        && facts.indexOperand(local.getInitializer().get()).filter(index -> index.offset() == 0
            && index.declarationId().equals(scan.declarationId())).isPresent();
  }

  private static boolean decrement(Statement statement, SortSemanticFacts.Counter scan) {
    if (!(statement instanceof ExpressionStmt expression)) return false;
    Expression value = SortSemanticFacts.unwrap(expression.getExpression());
    if (value instanceof UnaryExpr unary)
      return (unary.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT
          || unary.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT)
          && resolves(unary.getExpression(), scan.declarationId());
    if (!(value instanceof AssignExpr assignment)) return false;
    return assignment.getOperator() == AssignExpr.Operator.MINUS && resolves(assignment.getTarget(), scan.declarationId())
        && one(assignment.getValue());
  }

  private static AssignExpr assignment(Statement statement) {
    return statement instanceof ExpressionStmt expression && expression.getExpression() instanceof AssignExpr assign
        && assign.getOperator() == AssignExpr.Operator.ASSIGN ? assign : null;
  }

  private static boolean arrayAt(Expression expression, SortSemanticFacts.SequenceBinding sequence,
      SortSemanticFacts.Counter index, int offset, SortSemanticFacts.Analysis facts) {
    Expression value = SortSemanticFacts.unwrap(expression);
    if (!(value instanceof ArrayAccessExpr access)) return false;
    var receiver = facts.sequenceAccess(access);
    var operand = facts.indexOperand(access.getIndex());
    return receiver.isPresent() && operand.isPresent()
        && receiver.get().binding().canonicalDeclarationId().equals(sequence.canonicalDeclarationId())
        && operand.get().declarationId().equals(index.declarationId()) && operand.get().offset() == offset;
  }

  private static Element element(Expression expression, SortSemanticFacts.Analysis facts) {
    Expression value = SortSemanticFacts.unwrap(expression);
    if (!(value instanceof ArrayAccessExpr access)) return null;
    var sequence = facts.sequenceAccess(access);
    var index = facts.indexOperand(access.getIndex());
    return sequence.isPresent() && index.isPresent() ? new Element(sequence.get().binding(), index.get()) : null;
  }

  private static boolean comparisonOperand(SameFileHelperSummaries.Operand operand,
      SortSemanticFacts.SequenceBinding sequence, SortSemanticFacts.Counter scan, int offset) {
    return operand.sequence().isPresent() && operand.index().isPresent()
        && operand.sequence().get().canonicalDeclarationId().equals(sequence.canonicalDeclarationId())
        && operand.index().get().declarationId().equals(scan.declarationId()) && operand.index().get().offset() == offset;
  }

  private static boolean keyOperand(SameFileHelperSummaries.Operand operand, VariableDeclarator key) {
    return operand.sequence().isEmpty() && operand.index().isPresent()
        && operand.index().get().declarationId().equals(AstAnalyzer.nodeId(key));
  }

  private static boolean keyOperand(SameFileHelperSummaries.Operand operand, VariableDeclarator key,
      MethodCallExpr call) {
    if (keyOperand(operand, key)) return true;
    return operand.sequence().isEmpty()
        && operand.scalarType().filter(key.getType().asString()::equals).isPresent()
        && call.getArguments().stream().anyMatch(argument ->
            AstAnalyzer.nodeId(argument).equals(operand.astNodeId()) && keyOperand(argument, key));
  }

  private static boolean keyOperand(Expression expression, VariableDeclarator key) {
    return resolves(expression, AstAnalyzer.nodeId(key));
  }

  private static boolean resolves(Expression expression, String declarationId) {
    return SortSemanticFacts.unwrap(expression) instanceof NameExpr name
        && LocalVariableBindings.resolve(name).filter(declarationId::equals).isPresent();
  }

  private record Key(VariableDeclarator variable, SortSemanticFacts.SequenceBinding sequence) {}
  private record Scan(SortSemanticFacts.Counter counter) {}
  private record Element(SortSemanticFacts.SequenceBinding binding, SortSemanticFacts.IndexOperand index) {}
  private record SemanticCondition(SortPattern.Direction direction, String comparisonId,
      SameFileHelperSummaries.Comparison helperComparison,
      SortSemanticFacts.ComparatorBinding comparator) {}
  private record Movement(Statement shiftWrite, Statement decrement) {}

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
