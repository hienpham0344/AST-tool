package com.example.astchunker.algorithm.slidingwindow;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SlidingWindowPattern;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Conservative proof of a sum window over one indexed input. */
public final class SlidingWindowDetector {
  private record Input(String name, String id, String representation) {}
  private record Access(Input input, String indexId, Expression index, Node node) {}
  private record Write(String accumulatorId, String accumulatorName, Access access,
                       AssignExpr assignment) {}
  private record Scan(String name, String id, Input input) {}

  public Optional<AlgorithmHint> detect(CompilationUnit unit, Node loop) {
    if (!(loop instanceof ForStmt || loop instanceof WhileStmt)
        || LocalVariableBindings.callable(loop) == null || loop.getRange().isEmpty()
        || !loop.findAll(ContinueStmt.class).isEmpty()
        || !loop.findAll(BreakStmt.class).isEmpty()) return Optional.empty();
    Scan scan = scan(unit, loop);
    if (scan == null || !initialZero(unit, scan.id()) || mutatedBefore(loop, scan.id())
        || !advancesOnce(loop, scan.id())
        || rebound(loop, scan.input().id()) || unsafeCall(unit, loop, scan.input().id()))
      return Optional.empty();
    List<Write> additions = writes(unit, loop, true);
    List<Write> removals = writes(unit, loop, false);
    if (additions.size() != 1 || removals.size() != 1) return Optional.empty();
    Write add = additions.get(0), remove = removals.get(0);
    if (!add.accumulatorId().equals(remove.accumulatorId())
        || !add.access().input().id().equals(scan.input().id())
        || !remove.access().input().id().equals(scan.input().id())
        || !nameId(add.access().index(), scan.id())
        || !initialZero(unit, add.accumulatorId())
        || mutatedBefore(loop, add.accumulatorId())
        || !directOuterStatement(add.assignment(), loop)
        || !before(add.assignment(), remove.assignment())
        || extraAccumulatorWrite(loop, add.accumulatorId(), add.assignment(), remove.assignment()))
      return Optional.empty();
    Map<String, String> names = new LinkedHashMap<>();
    Map<String, String> ids = new LinkedHashMap<>();
    names.put("data", scan.input().name()); ids.put("data", scan.input().id());
    names.put("right", scan.name()); ids.put("right", scan.id());
    names.put("accumulator", add.accumulatorName());
    ids.put("accumulator", add.accumulatorId());
    String kind;
    if (fixed(unit, loop, scan, remove, names, ids)) kind = "FIXED_SIZE";
    else if (variable(unit, loop, scan, remove, names, ids)) kind = "VARIABLE_SIZE";
    else return Optional.empty();
    Map<String, String> operations = Map.of("add", AstAnalyzer.nodeId(add.assignment()),
        "remove", AstAnalyzer.nodeId(remove.assignment()),
        "guard", AstAnalyzer.nodeId(condition(loop)));
    List<String> evidence = List.of("Scan advances within one input bound",
        "Accumulator adds the entering value and removes an outgoing value from the same input",
        kind.equals("FIXED_SIZE") ? "Outgoing index is guarded by window size"
            : "Left index advances under a bounded shrink condition");
    SlidingWindowPattern pattern = new SlidingWindowPattern(1, kind,
        scan.input().representation(), ids, operations, evidence);
    var range = loop.getRange().orElseThrow();
    return Optional.of(new AlgorithmHint("sliding-window", 0.9, "array-window", evidence,
        names, range.begin.line, range.end.line, AstAnalyzer.nodeId(loop),
        AstAnalyzer.nodeId(LocalVariableBindings.callable(loop)), ids,
        null, null, null, null, null, null, pattern));
  }

  private static Scan scan(CompilationUnit unit, Node loop) {
    Expression condition = condition(loop);
    if (!(unwrap(condition) instanceof BinaryExpr guard)) return null;
    NameExpr pointer;
    Expression bound;
    if (guard.getOperator() == BinaryExpr.Operator.LESS
        && unwrap(guard.getLeft()) instanceof NameExpr name) {
      pointer = name; bound = guard.getRight();
    } else if (guard.getOperator() == BinaryExpr.Operator.GREATER
        && unwrap(guard.getRight()) instanceof NameExpr name) {
      pointer = name; bound = guard.getLeft();
    } else return null;
    String id = LocalVariableBindings.resolve(pointer).orElse(null);
    Input input = lengthInput(unit, bound);
    return id == null || input == null ? null : new Scan(pointer.getNameAsString(), id, input);
  }

  private static Input lengthInput(CompilationUnit unit, Expression expression) {
    Expression value = unwrap(expression);
    NameExpr name = null;
    String expected = null;
    if (value instanceof FieldAccessExpr field && field.getNameAsString().equals("length")
        && unwrap(field.getScope()) instanceof NameExpr source) {
      name = source; expected = "INT_ARRAY";
    } else if (value instanceof MethodCallExpr call && call.getNameAsString().equals("size")
        && call.getArguments().isEmpty() && call.getScope().isPresent()
        && unwrap(call.getScope().get()) instanceof NameExpr source) {
      name = source; expected = "INTEGER_LIST";
    }
    if (name == null) return null;
    String id = LocalVariableBindings.resolve(name).orElse(null);
    return id != null && expected.equals(representation(unit, id))
        ? new Input(name.getNameAsString(), id, expected) : null;
  }

  private static String representation(CompilationUnit unit, String id) {
    for (Parameter parameter : unit.findAll(Parameter.class))
      if (AstAnalyzer.nodeId(parameter).equals(id))
        return type(unit, parameter.getType().asString());
    for (VariableDeclarator variable : unit.findAll(VariableDeclarator.class))
      if (AstAnalyzer.nodeId(variable).equals(id))
        return type(unit, variable.getType().asString());
    return null;
  }

  private static String type(CompilationUnit unit, String raw) {
    String type = raw.replaceAll("\\s+", "");
    if (type.equals("int[]")) return "INT_ARRAY";
    if (type.matches("java\\.util\\.(List|ArrayList|LinkedList)<Integer>"))
      return "INTEGER_LIST";
    if (!type.matches("(List|ArrayList|LinkedList)<Integer>")) return null;
    String simple = type.substring(0, type.indexOf('<'));
    if (unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream()
        .anyMatch(declaration -> declaration.getNameAsString().equals(simple)
            || declaration.getNameAsString().equals("Integer"))) return null;
    return unit.getImports().stream().anyMatch(imported -> !imported.isStatic()
        && (imported.getNameAsString().equals("java.util." + simple)
            || imported.isAsterisk() && imported.getNameAsString().equals("java.util")))
        ? "INTEGER_LIST" : null;
  }

  private static Access access(CompilationUnit unit, Expression expression) {
    Expression value = unwrap(expression);
    NameExpr data, pointer = null;
    Expression index;
    if (value instanceof ArrayAccessExpr array
        && unwrap(array.getName()) instanceof NameExpr name) {
      data = name; index = unwrap(array.getIndex());
    } else if (value instanceof MethodCallExpr call && call.getNameAsString().equals("get")
        && call.getScope().isPresent() && call.getArguments().size() == 1
        && unwrap(call.getScope().get()) instanceof NameExpr name) {
      data = name; index = unwrap(call.getArgument(0));
    } else return null;
    String id = LocalVariableBindings.resolve(data).orElse(null);
    String representation = id == null ? null : representation(unit, id);
    if (representation == null || value instanceof ArrayAccessExpr
        && !representation.equals("INT_ARRAY")
        || value instanceof MethodCallExpr && !representation.equals("INTEGER_LIST")) return null;
    return new Access(new Input(data.getNameAsString(), id, representation),
        pointer == null ? null : LocalVariableBindings.resolve(pointer).orElse(null), index, value);
  }

  private static List<Write> writes(CompilationUnit unit, Node loop, boolean addition) {
    List<Write> result = new ArrayList<>();
    for (AssignExpr assignment : loop.findAll(AssignExpr.class)) {
      if (!(assignment.getTarget() instanceof NameExpr target)
          || !within(assignment, loop)) continue;
      String accumulatorId = LocalVariableBindings.resolve(target).orElse(null);
      if (accumulatorId == null) continue;
      Expression value = unwrap(assignment.getValue());
      Expression element = null;
      if (assignment.getOperator() == (addition ? AssignExpr.Operator.PLUS
          : AssignExpr.Operator.MINUS)) element = value;
      else if (assignment.getOperator() == AssignExpr.Operator.ASSIGN
          && value instanceof BinaryExpr binary
          && binary.getOperator() == (addition ? BinaryExpr.Operator.PLUS
              : BinaryExpr.Operator.MINUS)) {
        if (nameId(binary.getLeft(), accumulatorId)) element = binary.getRight();
        else if (addition && nameId(binary.getRight(), accumulatorId)) element = binary.getLeft();
      }
      Access access = element == null ? null : access(unit, element);
      if (access != null) result.add(new Write(accumulatorId,
          target.getNameAsString(), access, assignment));
    }
    return result;
  }

  private static boolean nameId(Expression expression, String id) {
    return unwrap(expression) instanceof NameExpr name
        && LocalVariableBindings.resolve(name).filter(id::equals).isPresent();
  }

  private static boolean initialZero(CompilationUnit unit, String id) {
    return unit.findAll(VariableDeclarator.class).stream()
        .filter(variable -> AstAnalyzer.nodeId(variable).equals(id))
        .flatMap(variable -> variable.getInitializer().stream())
        .map(SlidingWindowDetector::unwrap)
        .anyMatch(value -> value instanceof IntegerLiteralExpr literal
            && literal.asNumber().intValue() == 0);
  }

  private static Expression condition(Node loop) {
    if (loop instanceof ForStmt statement) return statement.getCompare().orElse(null);
    if (loop instanceof WhileStmt statement) return statement.getCondition();
    return null;
  }

  private static Expression unwrap(Expression expression) {
    while (expression instanceof EnclosedExpr enclosed) expression = enclosed.getInner();
    return expression;
  }

  private static boolean within(Node child, Node parent) {
    for (Node node = child; node != null; node = node.getParentNode().orElse(null))
      if (node == parent) return true;
    return false;
  }

  private static boolean before(Node first, Node second) {
    return first.getBegin().isPresent() && second.getBegin().isPresent()
        && first.getBegin().get().isBefore(second.getBegin().get());
  }

  private static boolean advancesOnce(Node loop, String id) {
    List<Node> updates = new ArrayList<>();
    for (UnaryExpr unary : loop.findAll(UnaryExpr.class))
      if (unary.getExpression() instanceof NameExpr name
          && LocalVariableBindings.resolve(name).filter(id::equals).isPresent())
        updates.add(unary);
    for (AssignExpr assignment : loop.findAll(AssignExpr.class))
      if (assignment.getTarget() instanceof NameExpr name
          && LocalVariableBindings.resolve(name).filter(id::equals).isPresent())
        updates.add(assignment);
    if (updates.size() != 1 || !plusOne(updates.get(0), id)) return false;
    Node update = updates.get(0);
    if (loop instanceof ForStmt statement)
      return statement.getUpdate().stream().anyMatch(expression -> expression == update);
    Statement body = ((WhileStmt) loop).getBody();
    return body instanceof BlockStmt block && !block.getStatements().isEmpty()
        && block.getStatement(block.getStatements().size() - 1) instanceof ExpressionStmt expr
        && expr.getExpression() == update;
  }

  private static boolean plusOne(Node update, String id) {
    if (update instanceof UnaryExpr unary)
      return unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
          || unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT;
    if (!(update instanceof AssignExpr assignment)) return false;
    if (assignment.getOperator() == AssignExpr.Operator.PLUS)
      return literalOne(assignment.getValue());
    if (assignment.getOperator() != AssignExpr.Operator.ASSIGN
        || !(unwrap(assignment.getValue()) instanceof BinaryExpr binary)
        || binary.getOperator() != BinaryExpr.Operator.PLUS) return false;
    return nameId(binary.getLeft(), id) && literalOne(binary.getRight())
        || nameId(binary.getRight(), id) && literalOne(binary.getLeft());
  }

  private static boolean literalOne(Expression expression) {
    return unwrap(expression) instanceof IntegerLiteralExpr literal
        && literal.asNumber().intValue() == 1;
  }

  private static boolean rebound(Node loop, String id) {
    Node callable = LocalVariableBindings.callable(loop);
    if (callable == null || loop.getEnd().isEmpty()) return true;
    boolean assigned = callable.findAll(AssignExpr.class).stream()
        .filter(assignment -> assignment.getTarget() instanceof NameExpr
            && assignment.getBegin().isPresent()
            && !assignment.getBegin().get().isAfter(loop.getEnd().get()))
        .map(assignment -> (NameExpr) assignment.getTarget())
        .anyMatch(name -> LocalVariableBindings.resolve(name).filter(id::equals).isPresent());
    return assigned || callable.findAll(UnaryExpr.class).stream()
        .filter(SlidingWindowDetector::incrementOrDecrement)
        .filter(unary -> unary.getExpression() instanceof NameExpr
            && unary.getBegin().isPresent()
            && !unary.getBegin().get().isAfter(loop.getEnd().get()))
        .map(unary -> (NameExpr) unary.getExpression())
        .anyMatch(name -> LocalVariableBindings.resolve(name).filter(id::equals).isPresent());
  }

  private static boolean mutatedBefore(Node loop, String id) {
    Node callable = LocalVariableBindings.callable(loop);
    if (callable == null || loop.getBegin().isEmpty()) return true;
    boolean assignment = callable.findAll(AssignExpr.class).stream()
        .filter(write -> write.getTarget() instanceof NameExpr
            && write.getBegin().isPresent()
            && write.getBegin().get().isBefore(loop.getBegin().get()))
        .map(write -> (NameExpr) write.getTarget())
        .anyMatch(name -> LocalVariableBindings.resolve(name).filter(id::equals).isPresent());
    return assignment || callable.findAll(UnaryExpr.class).stream()
        .filter(SlidingWindowDetector::incrementOrDecrement)
        .filter(update -> update.getExpression() instanceof NameExpr
            && update.getBegin().isPresent()
            && update.getBegin().get().isBefore(loop.getBegin().get()))
        .map(update -> (NameExpr) update.getExpression())
        .anyMatch(name -> LocalVariableBindings.resolve(name).filter(id::equals).isPresent());
  }

  private static boolean incrementOrDecrement(UnaryExpr unary) {
    return unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT
        || unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
        || unary.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT
        || unary.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT;
  }

  private static boolean unsafeCall(CompilationUnit unit, Node loop, String dataId) {
    if ("INT_ARRAY".equals(representation(unit, dataId))) {
      for (AssignExpr assignment : loop.findAll(AssignExpr.class))
        if (writesArrayElement(assignment.getTarget(), dataId)) return true;
      for (UnaryExpr unary : loop.findAll(UnaryExpr.class))
        if (incrementOrDecrement(unary)
            && writesArrayElement(unary.getExpression(), dataId)) return true;
    }
    for (MethodCallExpr call : loop.findAll(MethodCallExpr.class)) {
      if (!within(call, loop)) continue;
      if (!List.of("get", "size").contains(call.getNameAsString())
          || call.getScope().isEmpty()
          || !(unwrap(call.getScope().get()) instanceof NameExpr source)
          || LocalVariableBindings.resolve(source).filter(dataId::equals).isEmpty()
          || !"INTEGER_LIST".equals(representation(unit, dataId))) return true;
    }
    return false;
  }

  private static boolean writesArrayElement(Expression target, String dataId) {
    return unwrap(target) instanceof ArrayAccessExpr array
        && unwrap(array.getName()) instanceof NameExpr name
        && LocalVariableBindings.resolve(name).filter(dataId::equals).isPresent();
  }

  private static boolean directOuterStatement(Node assignment, Node loop) {
    Node parent = assignment.getParentNode().orElse(null);
    if (!(parent instanceof ExpressionStmt statement)
        || statement.getExpression() != assignment) return false;
    return directOuterChild(statement, loop);
  }

  private static boolean extraAccumulatorWrite(Node loop, String id,
                                               AssignExpr add, AssignExpr remove) {
    for (AssignExpr assignment : loop.findAll(AssignExpr.class)) {
      if (assignment == add || assignment == remove) continue;
      if (assignment.getTarget() instanceof NameExpr name
          && LocalVariableBindings.resolve(name).filter(id::equals).isPresent()) return true;
    }
    for (UnaryExpr unary : loop.findAll(UnaryExpr.class))
      if (unary.getExpression() instanceof NameExpr name
          && LocalVariableBindings.resolve(name).filter(id::equals).isPresent()) return true;
    return false;
  }

  private static boolean fixed(CompilationUnit unit, Node loop, Scan scan, Write remove,
                               Map<String, String> names, Map<String, String> ids) {
    if (!(remove.access().index() instanceof BinaryExpr offset)
        || offset.getOperator() != BinaryExpr.Operator.MINUS
        || !nameId(offset.getLeft(), scan.id())
        || !(unwrap(offset.getRight()) instanceof NameExpr size)) return false;
    String sizeId = LocalVariableBindings.resolve(size).orElse(null);
    if (sizeId == null || rebound(loop, sizeId) || !positiveSize(unit, loop, sizeId)
        || !guardedFixedRemoval(remove.assignment(), loop, scan.id(), sizeId)) return false;
    names.put("windowSize", size.getNameAsString()); ids.put("windowSize", sizeId);
    return true;
  }

  private static boolean positiveSize(CompilationUnit unit, Node loop, String id) {
    for (VariableDeclarator variable : unit.findAll(VariableDeclarator.class)) {
      if (!AstAnalyzer.nodeId(variable).equals(id)) continue;
      if (variable.getInitializer().isPresent()
          && unwrap(variable.getInitializer().get()) instanceof IntegerLiteralExpr literal
          && literal.asNumber().intValue() > 0) return true;
    }
    Node callable = LocalVariableBindings.callable(loop);
    if (callable == null || loop.getBegin().isEmpty()) return false;
    for (IfStmt branch : callable.findAll(IfStmt.class)) {
      if (branch.getBegin().isEmpty()
          || !branch.getBegin().get().isBefore(loop.getBegin().get())
          || branch.getParentNode().orElse(null) != loop.getParentNode().orElse(null)
          || !exits(branch.getThenStmt())) continue;
      if (rejectsNonPositive(branch.getCondition(), id)) return true;
    }
    return false;
  }

  private static boolean rejectsNonPositive(Expression expression, String id) {
    Expression value = unwrap(expression);
    if (!(value instanceof BinaryExpr binary)) return false;
    if (binary.getOperator() == BinaryExpr.Operator.OR)
      return rejectsNonPositive(binary.getLeft(), id)
          || rejectsNonPositive(binary.getRight(), id);
    return (binary.getOperator() == BinaryExpr.Operator.LESS_EQUALS
            && nameId(binary.getLeft(), id) && zero(binary.getRight()))
        || (binary.getOperator() == BinaryExpr.Operator.GREATER_EQUALS
            && zero(binary.getLeft()) && nameId(binary.getRight(), id));
  }

  private static boolean zero(Expression expression) {
    return unwrap(expression) instanceof IntegerLiteralExpr literal
        && literal.asNumber().intValue() == 0;
  }

  private static boolean exits(Statement statement) {
    if (statement instanceof ReturnStmt) return true;
    return statement instanceof BlockStmt block && block.getStatements().size() == 1
        && block.getStatement(0) instanceof ReturnStmt;
  }

  private static boolean guardedFixedRemoval(AssignExpr removal, Node loop,
                                              String scanId, String sizeId) {
    IfStmt branch = removal.findAncestor(IfStmt.class).orElse(null);
    if (branch == null || !directOuterChild(branch, loop)
        || !within(removal, branch.getThenStmt()))
      return false;
    Expression condition = unwrap(branch.getCondition());
    if (!(condition instanceof BinaryExpr binary)) return false;
    return binary.getOperator() == BinaryExpr.Operator.GREATER_EQUALS
            && nameId(binary.getLeft(), scanId) && nameId(binary.getRight(), sizeId)
        || binary.getOperator() == BinaryExpr.Operator.LESS_EQUALS
            && nameId(binary.getLeft(), sizeId) && nameId(binary.getRight(), scanId);
  }

  private static boolean variable(CompilationUnit unit, Node loop, Scan scan, Write remove,
                                  Map<String, String> names, Map<String, String> ids) {
    if (!(remove.access().index() instanceof NameExpr left)) return false;
    String leftId = LocalVariableBindings.resolve(left).orElse(null);
    if (leftId == null || leftId.equals(scan.id()) || !initialZero(unit, leftId)
        || mutatedBefore(loop, leftId)) return false;
    WhileStmt shrink = remove.assignment().findAncestor(WhileStmt.class).orElse(null);
    boolean proven = shrink != null && shrink != loop && directOuterChild(shrink, loop)
        && guardedShrink(shrink.getCondition(), scan.id(), leftId,
            remove.accumulatorId())
        && shrinkBody(shrink.getBody(), remove.assignment(), leftId);
    if (!proven) {
      IfStmt conditional = remove.assignment().findAncestor(IfStmt.class).orElse(null);
      proven = conditional != null && directOuterChild(conditional, loop)
          && guardedSpan(unit, loop, conditional.getCondition(), scan.id(), leftId)
          && shrinkBody(conditional.getThenStmt(), remove.assignment(), leftId);
    }
    if (!proven || leftWrites(loop, leftId) != 1) return false;
    names.put("left", left.getNameAsString()); ids.put("left", leftId);
    return true;
  }

  private static boolean guardedShrink(Expression expression, String scanId, String leftId,
                                       String accumulatorId) {
    Expression value = unwrap(expression);
    if (!(value instanceof BinaryExpr condition)
        || condition.getOperator() != BinaryExpr.Operator.AND) return false;
    return mentions(condition.getLeft(), accumulatorId)
        && leftBeforeScan(condition.getRight(), leftId, scanId)
        || mentions(condition.getRight(), accumulatorId)
            && leftBeforeScan(condition.getLeft(), leftId, scanId);
  }

  private static boolean leftBeforeScan(Expression expression, String leftId, String scanId) {
    if (!(unwrap(expression) instanceof BinaryExpr relation)) return false;
    return relation.getOperator() == BinaryExpr.Operator.LESS_EQUALS
            && nameId(relation.getLeft(), leftId) && nameId(relation.getRight(), scanId)
        || relation.getOperator() == BinaryExpr.Operator.GREATER_EQUALS
            && nameId(relation.getLeft(), scanId) && nameId(relation.getRight(), leftId);
  }

  private static boolean mentions(Expression expression, String id) {
    return expression.findAll(NameExpr.class).stream()
        .anyMatch(name -> LocalVariableBindings.resolve(name).filter(id::equals).isPresent());
  }

  private static boolean guardedSpan(CompilationUnit unit, Node loop, Expression expression,
                                     String scanId, String leftId) {
    if (!(unwrap(expression) instanceof BinaryExpr comparison)) return false;
    Expression span, size;
    if (comparison.getOperator() == BinaryExpr.Operator.GREATER) {
      span = comparison.getLeft(); size = comparison.getRight();
    } else if (comparison.getOperator() == BinaryExpr.Operator.LESS) {
      span = comparison.getRight(); size = comparison.getLeft();
    } else return false;
    if (!(unwrap(size) instanceof NameExpr name)) return false;
    String sizeId = LocalVariableBindings.resolve(name).orElse(null);
    if (sizeId == null || rebound(loop, sizeId) || !positiveSize(unit, loop, sizeId)
        || !(unwrap(span) instanceof BinaryExpr plus)
        || plus.getOperator() != BinaryExpr.Operator.PLUS
        || !literalOne(plus.getRight())
        || !(unwrap(plus.getLeft()) instanceof BinaryExpr difference)
        || difference.getOperator() != BinaryExpr.Operator.MINUS) return false;
    return nameId(difference.getLeft(), scanId)
        && nameId(difference.getRight(), leftId);
  }

  private static boolean shrinkBody(Statement body, AssignExpr removal, String leftId) {
    if (!(body instanceof BlockStmt block)
        || block.getStatements().size() != 2
        || !(block.getStatement(0) instanceof ExpressionStmt first)
        || first.getExpression() != removal
        || !(block.getStatement(1) instanceof ExpressionStmt second)) return false;
    Node update = second.getExpression();
    if (!plusOne(update, leftId)) return false;
    if (update instanceof UnaryExpr unary && unary.getExpression() instanceof NameExpr name)
      return LocalVariableBindings.resolve(name).filter(leftId::equals).isPresent();
    return update instanceof AssignExpr assignment
        && assignment.getTarget() instanceof NameExpr name
        && LocalVariableBindings.resolve(name).filter(leftId::equals).isPresent();
  }

  private static int leftWrites(Node loop, String id) {
    int count = 0;
    for (AssignExpr assignment : loop.findAll(AssignExpr.class))
      if (assignment.getTarget() instanceof NameExpr name
          && LocalVariableBindings.resolve(name).filter(id::equals).isPresent()) count++;
    for (UnaryExpr unary : loop.findAll(UnaryExpr.class))
      if (incrementOrDecrement(unary) && unary.getExpression() instanceof NameExpr name
          && LocalVariableBindings.resolve(name).filter(id::equals).isPresent()) count++;
    return count;
  }

  private static boolean directOuterChild(Statement statement, Node loop) {
    Node parent = statement.getParentNode().orElse(null);
    while (parent instanceof BlockStmt block)
      parent = block.getParentNode().orElse(null);
    return parent == loop;
  }
}
