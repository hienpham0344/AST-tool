package com.example.astchunker.algorithm.linearsearch;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.LinearSearchPattern;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Conservative proof of indexed search, independent of identifier spelling. */
public final class LinearSearchDetector {
  private record Input(String name, String id, String representation) {}
  private record Scan(String name, String id, Input input) {}
  private record Match(String targetName, String targetId, IfStmt branch,
                       ReturnStmt found, String resultKind) {}

  public Optional<AlgorithmHint> detect(CompilationUnit unit, Node loop) {
    if (!(loop instanceof ForStmt || loop instanceof WhileStmt)
        || !(LocalVariableBindings.callable(loop) instanceof MethodDeclaration method)
        || loop.getRange().isEmpty()
        || !loop.findAll(BreakStmt.class).isEmpty()
        || !loop.findAll(ContinueStmt.class).isEmpty()
        || !loop.findAll(ThrowStmt.class).isEmpty()) return Optional.empty();
    Scan scan = scan(unit, loop);
    if (scan == null || !startsAtZero(unit, loop, scan.id())
        || !advancesOnce(loop, scan.id())
        || writesBeforeEnd(method, loop, scan.input().id())
        || unsafeInputUse(method, loop, scan.input())) return Optional.empty();
    Match match = match(unit, loop, method, scan);
    if (match == null || match.targetId().equals(scan.id())
        || match.targetId().equals(scan.input().id())
        || declaredInside(unit, loop, match.targetId())
        || writesBeforeEnd(method, loop, match.targetId())
        || !exhaustion(loop, match.resultKind())) return Optional.empty();
    Map<String, String> names = new LinkedHashMap<>();
    names.put("data", scan.input().name());
    names.put("scan", scan.name());
    names.put("target", match.targetName());
    Map<String, String> ids = new LinkedHashMap<>();
    ids.put("data", scan.input().id());
    ids.put("scan", scan.id());
    ids.put("target", match.targetId());
    List<String> evidence = List.of("Index starts at zero and advances once within input bound",
        "Current element is compared with the target by equality",
        "Match returns a result; exhaustion returns the not-found value");
    LinearSearchPattern pattern = new LinearSearchPattern(1, scan.input().representation(),
        match.resultKind(), ids, Map.of("compare", AstAnalyzer.nodeId(match.branch().getCondition()),
            "foundReturn", AstAnalyzer.nodeId(match.found())), evidence);
    var range = loop.getRange().orElseThrow();
    return Optional.of(new AlgorithmHint("linear-search", 0.9, "indexed-search", evidence,
        names, range.begin.line, range.end.line, AstAnalyzer.nodeId(loop),
        AstAnalyzer.nodeId(method), ids, null, null, null, null, null, null, null, pattern));
  }

  private static Scan scan(CompilationUnit unit, Node loop) {
    Expression condition = loop instanceof ForStmt forLoop
        ? forLoop.getCompare().orElse(null) : ((WhileStmt) loop).getCondition();
    if (condition == null || !(unwrap(condition) instanceof BinaryExpr bound)) return null;
    NameExpr pointer;
    Expression limit;
    if (bound.getOperator() == BinaryExpr.Operator.LESS
        && unwrap(bound.getLeft()) instanceof NameExpr name) {
      pointer = name; limit = bound.getRight();
    } else if (bound.getOperator() == BinaryExpr.Operator.GREATER
        && unwrap(bound.getRight()) instanceof NameExpr name) {
      pointer = name; limit = bound.getLeft();
    } else return null;
    String scanId = LocalVariableBindings.resolve(pointer).orElse(null);
    Input input = input(unit, limit);
    return scanId == null || input == null ? null
        : new Scan(pointer.getNameAsString(), scanId, input);
  }

  private static Input input(CompilationUnit unit, Expression expression) {
    Expression value = unwrap(expression);
    NameExpr name;
    String representation;
    if (value instanceof FieldAccessExpr field && field.getNameAsString().equals("length")
        && unwrap(field.getScope()) instanceof NameExpr source) {
      name = source; representation = "INT_ARRAY";
    } else if (value instanceof MethodCallExpr call && call.getNameAsString().equals("size")
        && call.getArguments().isEmpty() && call.getScope().isPresent()
        && unwrap(call.getScope().get()) instanceof NameExpr source) {
      name = source; representation = "INTEGER_LIST";
    } else return null;
    String id = LocalVariableBindings.resolve(name).orElse(null);
    return id != null && representation.equals(type(unit, id))
        ? new Input(name.getNameAsString(), id, representation) : null;
  }

  private static String type(CompilationUnit unit, String id) {
    for (Parameter parameter : unit.findAll(Parameter.class))
      if (AstAnalyzer.nodeId(parameter).equals(id)) return knownType(unit, parameter.getType().asString());
    for (VariableDeclarator variable : unit.findAll(VariableDeclarator.class))
      if (AstAnalyzer.nodeId(variable).equals(id)) return knownType(unit, variable.getType().asString());
    return null;
  }

  private static String knownType(CompilationUnit unit, String raw) {
    String type = raw.replaceAll("\\s+", "");
    if (type.equals("int")) return "INT";
    if (type.equals("int[]")) return "INT_ARRAY";
    if (type.equals("java.util.List<Integer>")
        || type.equals("java.util.ArrayList<Integer>")
        || type.equals("java.util.LinkedList<Integer>")) return "INTEGER_LIST";
    if (!List.of("List<Integer>", "ArrayList<Integer>", "LinkedList<Integer>")
        .contains(type)) return null;
    String simple = type.substring(0, type.indexOf('<'));
    if (unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream()
        .anyMatch(declaration -> declaration.getNameAsString().equals(simple)
            || declaration.getNameAsString().equals("Integer"))) return null;
    return unit.getImports().stream().anyMatch(imported -> !imported.isStatic()
        && (imported.getNameAsString().equals("java.util." + simple)
            || imported.isAsterisk() && imported.getNameAsString().equals("java.util")))
        ? "INTEGER_LIST" : null;
  }

  private static boolean startsAtZero(CompilationUnit unit, Node loop, String scanId) {
    if (loop instanceof ForStmt forLoop) {
      if (forLoop.getInitialization().size() != 1
          || !(forLoop.getInitialization().get(0) instanceof VariableDeclarationExpr declaration)
          || declaration.getVariables().size() != 1) return false;
      VariableDeclarator variable = declaration.getVariable(0);
      return AstAnalyzer.nodeId(variable).equals(scanId)
          && variable.getInitializer().filter(LinearSearchDetector::zero).isPresent();
    }
    boolean initialized = unit.findAll(VariableDeclarator.class).stream()
        .filter(variable -> AstAnalyzer.nodeId(variable).equals(scanId))
        .anyMatch(variable -> variable.getInitializer().filter(LinearSearchDetector::zero).isPresent()
            && variable.getBegin().isPresent() && loop.getBegin().isPresent()
            && variable.getBegin().get().isBefore(loop.getBegin().get()));
    return initialized && !writesBefore(loop, scanId);
  }

  private static boolean advancesOnce(Node loop, String id) {
    List<Node> updates = new ArrayList<>();
    for (AssignExpr assignment : loop.findAll(AssignExpr.class))
      if (assignment.getTarget() instanceof NameExpr name && nameId(name, id)) updates.add(assignment);
    for (UnaryExpr unary : loop.findAll(UnaryExpr.class))
      if (changes(unary) && unary.getExpression() instanceof NameExpr name && nameId(name, id))
        updates.add(unary);
    if (updates.size() != 1 || !plusOne(updates.get(0), id)) return false;
    Node update = updates.get(0);
    if (loop instanceof ForStmt forLoop)
      return forLoop.getUpdate().size() == 1 && forLoop.getUpdate().get(0) == update;
    Statement body = ((WhileStmt) loop).getBody();
    return body instanceof BlockStmt block && !block.getStatements().isEmpty()
        && block.getStatement(block.getStatements().size() - 1) instanceof ExpressionStmt statement
        && statement.getExpression() == update;
  }

  private static boolean plusOne(Node update, String id) {
    if (update instanceof UnaryExpr unary)
      return unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT
          || unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT;
    if (!(update instanceof AssignExpr assignment)) return false;
    if (assignment.getOperator() == AssignExpr.Operator.PLUS) return one(assignment.getValue());
    if (assignment.getOperator() != AssignExpr.Operator.ASSIGN
        || !(unwrap(assignment.getValue()) instanceof BinaryExpr plus)
        || plus.getOperator() != BinaryExpr.Operator.PLUS) return false;
    return nameId(plus.getLeft(), id) && one(plus.getRight())
        || nameId(plus.getRight(), id) && one(plus.getLeft());
  }

  private static Match match(CompilationUnit unit, Node loop, MethodDeclaration method, Scan scan) {
    List<IfStmt> branches = loop.findAll(IfStmt.class);
    if (branches.size() != 1) return null;
    IfStmt branch = branches.get(0);
    if (!directOuterChild(branch, loop) || branch.getElseStmt().isPresent()
        || !(unwrap(branch.getCondition()) instanceof BinaryExpr equality)
        || equality.getOperator() != BinaryExpr.Operator.EQUALS) return null;
    NameExpr target = target(equality.getLeft(), equality.getRight(), scan);
    if (target == null) target = target(equality.getRight(), equality.getLeft(), scan);
    if (target == null) return null;
    String targetId = LocalVariableBindings.resolve(target).orElse(null);
    if (targetId == null || !"INT".equals(type(unit, targetId))) return null;
    ReturnStmt found = onlyReturn(branch.getThenStmt());
    if (found == null || loop.findAll(ReturnStmt.class).size() != 1) return null;
    String resultKind;
    if (method.getType().isPrimitiveType()
        && method.getType().asPrimitiveType().getType()
            == com.github.javaparser.ast.type.PrimitiveType.Primitive.INT
        && found.getExpression().filter(expression -> nameId(expression, scan.id())).isPresent())
      resultKind = "INDEX";
    else if (method.getType().isPrimitiveType()
        && method.getType().asPrimitiveType().getType()
            == com.github.javaparser.ast.type.PrimitiveType.Primitive.BOOLEAN
        && found.getExpression().filter(LinearSearchDetector::trueLiteral).isPresent())
      resultKind = "BOOLEAN";
    else return null;
    return new Match(target.getNameAsString(), targetId, branch, found, resultKind);
  }

  private static NameExpr target(Expression element,
                                 Expression candidate, Scan scan) {
    Expression value = unwrap(element);
    String inputId = scan.input().id();
    boolean access = value instanceof ArrayAccessExpr array
        && "INT_ARRAY".equals(scan.input().representation())
        && nameId(array.getName(), inputId) && nameId(array.getIndex(), scan.id())
        || value instanceof MethodCallExpr call
            && "INTEGER_LIST".equals(scan.input().representation())
            && call.getNameAsString().equals("get") && call.getScope().isPresent()
            && call.getArguments().size() == 1 && nameId(call.getScope().get(), inputId)
            && nameId(call.getArgument(0), scan.id());
    return access && unwrap(candidate) instanceof NameExpr name ? name : null;
  }

  private static boolean exhaustion(Node loop, String resultKind) {
    if (!(loop.getParentNode().orElse(null) instanceof BlockStmt block)) return false;
    int index = block.getStatements().indexOf(loop);
    if (index < 0 || index + 1 >= block.getStatements().size()
        || !(block.getStatement(index + 1) instanceof ReturnStmt result)
        || result.getExpression().isEmpty()) return false;
    Expression value = unwrap(result.getExpression().get());
    return resultKind.equals("BOOLEAN") ? value instanceof BooleanLiteralExpr literal && !literal.getValue()
        : value instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS
            && one(unary.getExpression());
  }

  private static ReturnStmt onlyReturn(Statement statement) {
    if (statement instanceof ReturnStmt result) return result;
    if (statement instanceof BlockStmt block && block.getStatements().size() == 1
        && block.getStatement(0) instanceof ReturnStmt result) return result;
    return null;
  }

  private static boolean unsafeInputUse(MethodDeclaration method, Node loop, Input input) {
    if (!loop.findAll(ObjectCreationExpr.class).isEmpty()) return true;
    for (VariableDeclarator variable : method.findAll(VariableDeclarator.class))
      if (variable.getInitializer().filter(value -> nameId(value, input.id())).isPresent()
          && variable.getBegin().isPresent() && loop.getEnd().isPresent()
          && !variable.getBegin().get().isAfter(loop.getEnd().get())) return true;
    for (AssignExpr assignment : method.findAll(AssignExpr.class))
      if (nameId(assignment.getValue(), input.id())
          && assignment.getBegin().isPresent() && loop.getEnd().isPresent()
          && !assignment.getBegin().get().isAfter(loop.getEnd().get())) return true;
    for (AssignExpr assignment : loop.findAll(AssignExpr.class))
      if (arrayTarget(assignment.getTarget(), input.id())) return true;
    for (UnaryExpr unary : loop.findAll(UnaryExpr.class))
      if (changes(unary) && arrayTarget(unary.getExpression(), input.id())) return true;
    for (MethodCallExpr call : loop.findAll(MethodCallExpr.class)) {
      if (!"INTEGER_LIST".equals(input.representation())
          || !List.of("get", "size").contains(call.getNameAsString())
          || call.getScope().isEmpty() || !nameId(call.getScope().get(), input.id())
          || call.getArguments().size() != (call.getNameAsString().equals("get") ? 1 : 0))
        return true;
    }
    return false;
  }

  private static boolean arrayTarget(Expression expression, String inputId) {
    return unwrap(expression) instanceof ArrayAccessExpr array && nameId(array.getName(), inputId);
  }

  private static boolean declaredInside(CompilationUnit unit, Node loop, String id) {
    return unit.findAll(VariableDeclarator.class).stream()
        .anyMatch(variable -> AstAnalyzer.nodeId(variable).equals(id) && within(variable, loop));
  }

  private static boolean within(Node child, Node parent) {
    for (Node node = child; node != null; node = node.getParentNode().orElse(null))
      if (node == parent) return true;
    return false;
  }

  private static boolean writesBeforeEnd(MethodDeclaration method, Node loop, String id) {
    if (loop.getEnd().isEmpty()) return true;
    for (AssignExpr assignment : method.findAll(AssignExpr.class))
      if (assignment.getBegin().isPresent() && !assignment.getBegin().get().isAfter(loop.getEnd().get())
          && assignment.getTarget() instanceof NameExpr name && nameId(name, id)) return true;
    for (UnaryExpr unary : method.findAll(UnaryExpr.class))
      if (changes(unary) && unary.getBegin().isPresent()
          && !unary.getBegin().get().isAfter(loop.getEnd().get())
          && unary.getExpression() instanceof NameExpr name && nameId(name, id)) return true;
    return false;
  }

  private static boolean writesBefore(Node loop, String id) {
    if (!(LocalVariableBindings.callable(loop) instanceof MethodDeclaration method)
        || loop.getBegin().isEmpty()) return true;
    for (AssignExpr assignment : method.findAll(AssignExpr.class))
      if (assignment.getBegin().isPresent() && assignment.getBegin().get().isBefore(loop.getBegin().get())
          && assignment.getTarget() instanceof NameExpr name && nameId(name, id)) return true;
    for (UnaryExpr unary : method.findAll(UnaryExpr.class))
      if (changes(unary) && unary.getBegin().isPresent()
          && unary.getBegin().get().isBefore(loop.getBegin().get())
          && unary.getExpression() instanceof NameExpr name && nameId(name, id)) return true;
    return false;
  }

  private static boolean directOuterChild(Statement statement, Node loop) {
    Node parent = statement.getParentNode().orElse(null);
    while (parent instanceof BlockStmt block) parent = block.getParentNode().orElse(null);
    return parent == loop;
  }

  private static boolean changes(UnaryExpr unary) {
    return unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT
        || unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
        || unary.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT
        || unary.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT;
  }

  private static boolean nameId(Expression expression, String id) {
    return unwrap(expression) instanceof NameExpr name
        && LocalVariableBindings.resolve(name).filter(id::equals).isPresent();
  }

  private static boolean zero(Expression expression) {
    return unwrap(expression) instanceof IntegerLiteralExpr literal
        && literal.asNumber().intValue() == 0;
  }

  private static boolean one(Expression expression) {
    return unwrap(expression) instanceof IntegerLiteralExpr literal
        && literal.asNumber().intValue() == 1;
  }

  private static boolean trueLiteral(Expression expression) {
    return unwrap(expression) instanceof BooleanLiteralExpr literal && literal.getValue();
  }

  private static Expression unwrap(Expression expression) {
    while (expression instanceof EnclosedExpr enclosed) expression = enclosed.getInner();
    return expression;
  }
}
