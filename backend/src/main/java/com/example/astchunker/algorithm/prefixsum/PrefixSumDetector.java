package com.example.astchunker.algorithm.prefixsum;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.PrefixSumPattern;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Conservative lexical/control-flow proof for canonical separate-array prefix sums. */
public final class PrefixSumDetector {
  private record Role(String name, String id, Node declaration) {}

  private record Scan(Role index, Role data, Expression update, List<Statement> body) {}

  private record Recurrence(Role prefix, AssignExpr write, boolean sentinel) {}

  public Optional<AlgorithmHint> detect(CompilationUnit unit, Node loop) {
    if (!(loop instanceof ForStmt || loop instanceof WhileStmt)
        || !(loop.getParentNode().orElse(null) instanceof BlockStmt block)
        || !(LocalVariableBindings.callable(loop) instanceof MethodDeclaration method))
      return Optional.empty();
    Scan scan = scan(unit, loop, block);
    if (scan == null || !provenInput(scan.data(), loop)) return Optional.empty();
    Recurrence recurrence = recurrence(unit, scan);
    if (recurrence == null || recurrence.prefix().id().equals(scan.data().id()))
      return Optional.empty();
    VariableDeclarator output =
        recurrence.prefix().declaration() instanceof VariableDeclarator v ? v : null;
    if (output == null
        || !dominates(output, loop)
        || !before(output, loop)
        || !arrayType(output.getType()).equals(arrayType(type(scan.data()))))
      return Optional.empty();
    Expression initializer = output.getInitializer().map(PrefixSumDetector::unwrap).orElse(null);
    if (!(initializer instanceof ArrayCreationExpr allocation)
        || allocation.getLevels().size() != 1
        || allocation.getInitializer().isPresent()
        || allocation.getLevels().get(0).getDimension().isEmpty()
        || !arrayType(output.getType()).equals(allocation.getElementType().asString())
        || !size(
            allocation.getLevels().get(0).getDimension().orElseThrow(),
            scan.data(),
            recurrence.sentinel())) return Optional.empty();
    int start = recurrence.sentinel() ? 0 : 1;
    if (!starts(loop, block, scan.index(), start) || !safeIndex(method, loop, scan))
      return Optional.empty();
    AssignExpr seed = seed(method, block, loop, scan, recurrence);
    if (seed == null && !recurrence.sentinel()) return Optional.empty();
    if (!safeArrays(method, loop, scan, recurrence, seed)) return Optional.empty();
    Map<String, String> ids =
        Map.of(
            "data",
            scan.data().id(),
            "prefix",
            recurrence.prefix().id(),
            "index",
            scan.index().id());
    Map<String, String> operations = new LinkedHashMap<>();
    operations.put("allocation", AstAnalyzer.nodeId(output));
    operations.put("seed", AstAnalyzer.nodeId(seed == null ? output : seed));
    operations.put("recurrence", AstAnalyzer.nodeId(recurrence.write()));
    operations.put("loop", AstAnalyzer.nodeId(loop));
    operations.put("indexUpdate", AstAnalyzer.nodeId(scan.update()));
    List<String> evidence =
        List.of(
            "Fresh separate primitive output has the matching source length",
            seed == null
                ? "Fresh primitive output proves an implicit zero sentinel"
                : "Direct seed dominates construction",
            "Index advances once; each iteration performs one direct prefix recurrence",
            "Participating arrays have no aliases, source writes, escapes or extra output writes");
    var pattern =
        new PrefixSumPattern(
            1,
            recurrence.sentinel() ? "ZERO_SENTINEL" : "SAME_LENGTH",
            ids,
            operations,
            queries(unit, method, loop, recurrence),
            evidence);
    var range = loop.getRange().orElseThrow();
    return Optional.of(
        new AlgorithmHint(
            "prefix-sum",
            0.95,
            "prefix-arrays",
            evidence,
            Map.of(
                "data",
                scan.data().name(),
                "prefix",
                recurrence.prefix().name(),
                "index",
                scan.index().name()),
            range.begin.line,
            range.end.line,
            AstAnalyzer.nodeId(loop),
            AstAnalyzer.nodeId(method),
            ids,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            pattern));
  }

  private static boolean provenInput(Role data, Node loop) {
    if (data.declaration() instanceof Parameter) return true;
    if (!(data.declaration() instanceof VariableDeclarator declaration)
        || !dominates(declaration, loop)) return false;
    Expression initializer =
        declaration.getInitializer().map(PrefixSumDetector::unwrap).orElse(null);
    return initializer instanceof ArrayCreationExpr || initializer instanceof ArrayInitializerExpr;
  }

  private static Scan scan(CompilationUnit unit, Node loop, BlockStmt block) {
    Expression condition =
        loop instanceof ForStmt f ? f.getCompare().orElse(null) : ((WhileStmt) loop).getCondition();
    if (!(unwrap(condition) instanceof BinaryExpr b) || b.getOperator() != BinaryExpr.Operator.LESS)
      return null;
    Role index = role(unit, b.getLeft());
    Expression limit = unwrap(b.getRight());
    if (index == null
        || !type(index).isPrimitiveType()
        || !type(index).asString().equals("int")
        || !(limit instanceof FieldAccessExpr length)
        || !length.getNameAsString().equals("length")) return null;
    Role data = role(unit, length.getScope());
    if (data == null || arrayType(type(data)).isEmpty()) return null;
    Statement body = loop instanceof ForStmt f ? f.getBody() : ((WhileStmt) loop).getBody();
    List<Statement> statements = statements(body);
    Expression update;
    if (loop instanceof ForStmt f) {
      if (f.getUpdate().size() != 1 || statements.isEmpty()) return null;
      update = f.getUpdate().get(0);
    } else {
      if (statements.size() < 2
          || !(statements.get(statements.size() - 1) instanceof ExpressionStmt e)) return null;
      update = e.getExpression();
    }
    return increments(update, index) ? new Scan(index, data, update, statements) : null;
  }

  private static Recurrence recurrence(CompilationUnit unit, Scan scan) {
    Recurrence result = null;
    for (Statement body : scan.body()) {
      if (!(body instanceof ExpressionStmt statement)) return null;
      Expression expression = unwrap(statement.getExpression());
      if (expression == scan.update()) continue;
      if (!(expression instanceof AssignExpr assignment)
          || !(unwrap(assignment.getTarget()) instanceof ArrayAccessExpr)) {
        if (!ordinary(expression, scan)) return null;
        continue;
      }
      Recurrence candidate = recurrenceWrite(unit, scan, expression);
      if (candidate == null || result != null) return null;
      result = candidate;
    }
    return result;
  }

  private static boolean ordinary(Expression expression, Scan scan) {
    if (!expression.findAll(MethodCallExpr.class).isEmpty()
        || !expression.findAll(ObjectCreationExpr.class).isEmpty()
        || !expression.findAll(ArrayCreationExpr.class).isEmpty()
        || !expression.findAll(LambdaExpr.class).isEmpty()
        || expression.findAll(UnaryExpr.class).stream().anyMatch(PrefixSumDetector::mutation))
      return false;
    if (expression instanceof VariableDeclarationExpr declarations) {
      return declarations.getVariables().stream().allMatch(v -> v.getType().isPrimitiveType())
          && expression.findAll(AssignExpr.class).isEmpty();
    }
    return expression instanceof AssignExpr assignment
        && unwrap(assignment.getTarget()) instanceof NameExpr
        && !ref(assignment.getTarget(), scan.index())
        && assignment.findAll(AssignExpr.class).size() == 1;
  }

  private static Recurrence recurrenceWrite(
      CompilationUnit unit, Scan scan, Expression expression) {
    if (!(expression instanceof AssignExpr write)
        || write.getOperator() != AssignExpr.Operator.ASSIGN
        || !(unwrap(write.getTarget()) instanceof ArrayAccessExpr target)
        || !(unwrap(write.getValue()) instanceof BinaryExpr sum)
        || sum.getOperator() != BinaryExpr.Operator.PLUS) return null;
    Role prefix = role(unit, target.getName());
    if (prefix == null) return null;
    for (boolean sentinel : List.of(false, true)) {
      if (!index(target.getIndex(), scan.index(), sentinel ? 1 : 0)) continue;
      if (addends(sum.getLeft(), sum.getRight(), prefix, scan, sentinel)
          || addends(sum.getRight(), sum.getLeft(), prefix, scan, sentinel))
        return new Recurrence(prefix, write, sentinel);
    }
    return null;
  }

  private static boolean addends(
      Expression prior, Expression data, Role prefix, Scan scan, boolean sentinel) {
    return access(prior, prefix, scan.index(), sentinel ? 0 : -1)
        && access(data, scan.data(), scan.index(), 0);
  }

  private static AssignExpr seed(
      MethodDeclaration method, BlockStmt block, Node loop, Scan scan, Recurrence r) {
    for (AssignExpr assignment : method.findAll(AssignExpr.class)) {
      if (assignment.getOperator() != AssignExpr.Operator.ASSIGN
          || !(unwrap(assignment.getTarget()) instanceof ArrayAccessExpr cell)
          || !ref(cell.getName(), r.prefix())
          || !literal(cell.getIndex(), 0)
          || directStatement(assignment, block) == null
          || !before(assignment, loop)
          || !before(r.prefix().declaration(), assignment)) continue;
      if (r.sentinel()
          ? literal(assignment.getValue(), 0)
          : unwrap(assignment.getValue()) instanceof ArrayAccessExpr source
              && ref(source.getName(), scan.data())
              && literal(source.getIndex(), 0)) return assignment;
    }
    return null;
  }

  private static boolean starts(Node loop, BlockStmt block, Role index, int start) {
    if (!(index.declaration() instanceof VariableDeclarator declaration)
        || declaration.getInitializer().filter(e -> literal(e, start)).isEmpty()) return false;
    if (loop instanceof ForStmt f)
      return f.getInitialization().size() == 1
          && f.getInitialization().get(0) instanceof VariableDeclarationExpr variables
          && variables.getVariables().size() == 1
          && variables.getVariable(0) == declaration;
    return directStatement(declaration, block) != null && before(declaration, loop);
  }

  private static boolean safeIndex(MethodDeclaration method, Node loop, Scan scan) {
    for (AssignExpr write : method.findAll(AssignExpr.class))
      if (ref(write.getTarget(), scan.index()) && write != scan.update()) return false;
    for (UnaryExpr unary : method.findAll(UnaryExpr.class))
      if (mutation(unary) && ref(unary.getExpression(), scan.index()) && unary != scan.update())
        return false;
    return method.findAll(NameExpr.class).stream()
        .filter(n -> n.getNameAsString().equals(scan.index().name()) && loop.isAncestorOf(n))
        .allMatch(n -> ref(n, scan.index()));
  }

  private static boolean safeArrays(
      MethodDeclaration method, Node loop, Scan scan, Recurrence r, AssignExpr seed) {
    for (Role array : List.of(scan.data(), r.prefix())) {
      for (NameExpr name : method.findAll(NameExpr.class)) {
        if (!name.getNameAsString().equals(array.name())) continue;
        if (!ref(name, array)) return false;
        Node use = outer(name).getParentNode().orElse(null);
        if (use instanceof FieldAccessExpr field
            && outer(name) == field.getScope()
            && field.getNameAsString().equals("length")) continue;
        if (use instanceof ArrayAccessExpr cell && outer(name) == cell.getName()) {
          Node operation = outer(cell).getParentNode().orElse(null);
          if (operation instanceof AssignExpr write && outer(cell) == write.getTarget()) {
            if (array == scan.data() || write != seed && write != r.write()) return false;
          }
          if (operation instanceof UnaryExpr unary && mutation(unary)) return false;
          continue;
        }
        if (array == r.prefix()
            && use instanceof ReturnStmt returned
            && returned.getParentNode().orElse(null) == loop.getParentNode().orElse(null)
            && before(loop, returned)) continue;
        return false;
      }
    }
    return true;
  }

  private static List<PrefixSumPattern.Query> queries(
      CompilationUnit unit, MethodDeclaration method, Node loop, Recurrence r) {
    if (!r.sentinel() || loop.getParentNode().orElse(null) != method.getBody().orElse(null))
      return List.of();
    List<PrefixSumPattern.Query> result = new ArrayList<>();
    for (BinaryExpr difference : method.findAll(BinaryExpr.class)) {
      if (difference.getOperator() != BinaryExpr.Operator.MINUS
          || !before(loop, difference)
          || !(unwrap(difference.getLeft()) instanceof ArrayAccessExpr upper)
          || !(unwrap(difference.getRight()) instanceof ArrayAccessExpr lower)
          || !ref(upper.getName(), r.prefix())
          || !ref(lower.getName(), r.prefix())
          || !(unwrap(upper.getIndex()) instanceof BinaryExpr plus)
          || plus.getOperator() != BinaryExpr.Operator.PLUS
          || !literal(plus.getRight(), 1)) continue;
      Role left = role(unit, lower.getIndex());
      Role right = role(unit, plus.getLeft());
      if (left == null
          || right == null
          || !type(left).asString().equals("int")
          || !type(right).asString().equals("int")) continue;
      Node use = outer(difference).getParentNode().orElse(null);
      Statement statement = directStatement(difference, method.getBody().orElseThrow());
      boolean direct =
          use instanceof ReturnStmt
              || use instanceof VariableDeclarator variable
                  && variable.getInitializer().orElse(null) == outer(difference)
              || use instanceof AssignExpr assignment
                  && assignment.getValue() == outer(difference)
                  && unwrap(assignment.getTarget()) instanceof NameExpr;
      if (!direct || statement == null) continue;
      result.add(
          new PrefixSumPattern.Query(
              AstAnalyzer.nodeId(difference),
              left.id(),
              right.id(),
              AstAnalyzer.nodeId(unwrap(lower.getIndex())),
              AstAnalyzer.nodeId(unwrap(plus.getLeft()))));
    }
    return List.copyOf(result);
  }

  private static Role role(CompilationUnit unit, Expression expression) {
    if (!(unwrap(expression) instanceof NameExpr name)) return null;
    String id = LocalVariableBindings.resolve(name).orElse(null);
    if (id == null) return null;
    Node declaration =
        unit.findAll(Node.class).stream()
            .filter(n -> n instanceof VariableDeclarator || n instanceof Parameter)
            .filter(n -> AstAnalyzer.nodeId(n).equals(id))
            .findFirst()
            .orElse(null);
    return declaration == null ? null : new Role(name.getNameAsString(), id, declaration);
  }

  private static Type type(Role role) {
    return role.declaration() instanceof VariableDeclarator v
        ? v.getType()
        : ((Parameter) role.declaration()).getType();
  }

  private static String arrayType(Type type) {
    if (!type.isArrayType() || !type.asArrayType().getComponentType().isPrimitiveType()) return "";
    String name = type.asArrayType().getComponentType().asString();
    return name.equals("int") || name.equals("long") ? name : "";
  }

  private static boolean size(Expression expression, Role data, boolean sentinel) {
    Expression size = unwrap(expression);
    if (sentinel) {
      if (!(size instanceof BinaryExpr plus) || plus.getOperator() != BinaryExpr.Operator.PLUS)
        return false;
      if (literal(plus.getRight(), 1)) size = unwrap(plus.getLeft());
      else if (literal(plus.getLeft(), 1)) size = unwrap(plus.getRight());
      else return false;
    }
    return size instanceof FieldAccessExpr length
        && length.getNameAsString().equals("length")
        && ref(length.getScope(), data);
  }

  private static boolean increments(Expression expression, Role index) {
    Expression value = unwrap(expression);
    if (value instanceof UnaryExpr unary)
      return (unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
              || unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT)
          && ref(unary.getExpression(), index);
    return value instanceof AssignExpr assignment
        && ref(assignment.getTarget(), index)
        && (assignment.getOperator() == AssignExpr.Operator.PLUS
                && literal(assignment.getValue(), 1)
            || assignment.getOperator() == AssignExpr.Operator.ASSIGN
                && index(assignment.getValue(), index, 1));
  }

  private static boolean access(Expression expression, Role array, Role index, int offset) {
    return unwrap(expression) instanceof ArrayAccessExpr access
        && ref(access.getName(), array)
        && index(access.getIndex(), index, offset);
  }

  private static boolean index(Expression expression, Role index, int offset) {
    Expression value = unwrap(expression);
    if (offset == 0) return ref(value, index);
    if (!(value instanceof BinaryExpr b)) return false;
    if (offset == 1 && b.getOperator() == BinaryExpr.Operator.PLUS)
      return ref(b.getLeft(), index) && literal(b.getRight(), 1)
          || literal(b.getLeft(), 1) && ref(b.getRight(), index);
    return offset == -1
        && b.getOperator() == BinaryExpr.Operator.MINUS
        && ref(b.getLeft(), index)
        && literal(b.getRight(), 1);
  }

  private static boolean ref(Expression expression, Role role) {
    return unwrap(expression) instanceof NameExpr name
        && LocalVariableBindings.resolve(name).filter(role.id()::equals).isPresent();
  }

  private static boolean literal(Expression expression, int expected) {
    Expression value = unwrap(expression);
    try {
      return value instanceof IntegerLiteralExpr literal
              && literal.asNumber().longValue() == expected
          || value instanceof LongLiteralExpr longLiteral
              && longLiteral.asNumber().longValue() == expected;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private static boolean mutation(UnaryExpr unary) {
    return switch (unary.getOperator()) {
      case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> true;
      default -> false;
    };
  }

  private static List<Statement> statements(Statement body) {
    return body instanceof BlockStmt block ? List.copyOf(block.getStatements()) : List.of(body);
  }

  private static Statement directStatement(Node node, BlockStmt block) {
    Node current = node;
    while (current != null && current.getParentNode().orElse(null) != block) {
      Node parent = current.getParentNode().orElse(null);
      if (!(parent instanceof ExpressionStmt
          || parent instanceof VariableDeclarationExpr
          || parent instanceof VariableDeclarator
          || parent instanceof EnclosedExpr
          || parent instanceof AssignExpr
          || parent instanceof ReturnStmt)) return null;
      current = parent;
    }
    return current instanceof Statement statement ? statement : null;
  }

  private static boolean dominates(Node declaration, Node loop) {
    for (Node current = loop; current != null; current = current.getParentNode().orElse(null)) {
      if (current.getParentNode().orElse(null) instanceof BlockStmt block
          && directStatement(declaration, block) != null
          && before(declaration, current)) return true;
    }
    return false;
  }

  private static boolean before(Node first, Node second) {
    return first.getEnd().isPresent()
        && second.getBegin().isPresent()
        && first.getEnd().orElseThrow().isBefore(second.getBegin().orElseThrow());
  }

  private static Node outer(Node node) {
    while (node.getParentNode().orElse(null) instanceof EnclosedExpr parent) node = parent;
    return node;
  }

  private static Expression unwrap(Expression expression) {
    while (expression instanceof EnclosedExpr enclosed) expression = enclosed.getInner();
    return expression;
  }
}
