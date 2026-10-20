package com.example.astchunker.algorithm.bfs;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.BfsPattern;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.TypeParameter;
import java.util.*;

/** Deliberately narrow proof of FIFO traversal, using lexical declaration identities. */
public final class BfsDetector {
  private static final Set<String> ENQUEUE = Set.of("add", "offer", "addLast", "offerLast");
  private static final Set<String> DEQUEUE = Set.of("poll", "remove", "pollFirst", "removeFirst");

  public Optional<AlgorithmHint> detect(CompilationUnit unit, Node loop) {
    if (!(loop instanceof WhileStmt || loop instanceof ForStmt)
        || !(loop.getParentNode().orElse(null) instanceof BlockStmt parent)
        || !(LocalVariableBindings.callable(loop) instanceof MethodDeclaration method))
      return Optional.empty();
    Expression condition;
    Statement body;
    if (loop instanceof WhileStmt w) {
      condition = w.getCondition();
      body = w.getBody();
    } else {
      ForStmt f = (ForStmt) loop;
      if (!f.getInitialization().isEmpty() || !f.getUpdate().isEmpty() || f.getCompare().isEmpty())
        return Optional.empty();
      condition = f.getCompare().get();
      body = f.getBody();
    }
    if (!(unwrap(condition) instanceof UnaryExpr neg)
        || neg.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
        || !(unwrap(neg.getExpression()) instanceof MethodCallExpr empty)
        || !empty.getNameAsString().equals("isEmpty")
        || !empty.getArguments().isEmpty()) return Optional.empty();
    NameExpr queue = scope(empty);
    if (queue == null || !(body instanceof BlockStmt block) || block.getStatements().size() != 2)
      return Optional.empty();
    VariableDeclarator q = declaration(unit, queue);
    if (q == null || !queueType(unit, q)) return Optional.empty();
    VariableDeclarator current = variable(block.getStatement(0));
    if (current == null
        || !current.getType().asString().equals("int")
        || !(current.getInitializer().orElse(null) instanceof MethodCallExpr take)
        || !call(take, queue, DEQUEUE, 0)) return Optional.empty();
    Statement neighbors = block.getStatement(1);
    NameExpr graph;
    VariableDeclarator neighbor;
    String representation;
    Statement neighborBody;
    if (neighbors instanceof ForEachStmt each) {
      if (each.getVariable().getVariables().size() != 1
          || !(each.getIterable() instanceof MethodCallExpr get)
          || !get.getNameAsString().equals("get")
          || get.getArguments().size() != 1
          || !ref(get.getArgument(0), current)) return Optional.empty();
      graph = scope(get);
      neighbor = each.getVariable().getVariable(0);
      representation = "ADJACENCY_LIST";
      neighborBody = each.getBody();
    } else if (neighbors instanceof ForStmt scan) {
      if (scan.getInitialization().size() != 1
          || !(scan.getInitialization().get(0) instanceof VariableDeclarationExpr decl)
          || decl.getVariables().size() != 1
          || scan.getCompare().isEmpty()
          || scan.getUpdate().size() != 1) return Optional.empty();
      neighbor = decl.getVariable(0);
      if (!neighbor.getInitializer().filter(BfsDetector::zero).isPresent()
          || !(unwrap(scan.getCompare().get()) instanceof BinaryExpr bound)
          || bound.getOperator() != BinaryExpr.Operator.LESS
          || !ref(bound.getLeft(), neighbor)
          || !(unwrap(bound.getRight()) instanceof FieldAccessExpr length)
          || !length.getNameAsString().equals("length")
          || !(unwrap(length.getScope()) instanceof NameExpr g)
          || !(scan.getUpdate().get(0) instanceof UnaryExpr increment)
          || !Set.of(UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.PREFIX_INCREMENT)
              .contains(increment.getOperator())
          || !ref(increment.getExpression(), neighbor)) return Optional.empty();
      graph = g;
      representation = "ADJACENCY_MATRIX";
      neighborBody = scan.getBody();
    } else return Optional.empty();
    if (graph == null || !neighbor.getType().asString().equals("int")) return Optional.empty();
    String graphType = type(unit, graph);
    if (representation.equals("ADJACENCY_MATRIX")
        ? !"int[][]".equals(graphType)
        : !listType(unit, graphType)) return Optional.empty();
    List<Statement> neighborStatements = statements(neighborBody);
    if (neighborStatements.size() != 1
        || !(neighborStatements.get(0) instanceof IfStmt guard)
        || guard.getElseStmt().isPresent()) return Optional.empty();
    Expression test = unwrap(guard.getCondition());
    Expression edge = null;
    if (representation.equals("ADJACENCY_MATRIX")) {
      if (!(test instanceof BinaryExpr and) || and.getOperator() != BinaryExpr.Operator.AND)
        return Optional.empty();
      edge = unwrap(and.getLeft());
      test = unwrap(and.getRight());
      if (!(edge instanceof BinaryExpr cmp)
          || cmp.getOperator() != BinaryExpr.Operator.NOT_EQUALS
          || !zero(cmp.getRight())
          || !(unwrap(cmp.getLeft()) instanceof ArrayAccessExpr outer)
          || !ref(outer.getIndex(), neighbor)
          || !(unwrap(outer.getName()) instanceof ArrayAccessExpr inner)
          || !same(inner.getName(), graph)
          || !ref(inner.getIndex(), current)) return Optional.empty();
    }
    if (!(test instanceof UnaryExpr unseen)
        || unseen.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
        || !(unwrap(unseen.getExpression()) instanceof ArrayAccessExpr seen)
        || !(unwrap(seen.getName()) instanceof NameExpr visited)
        || !ref(seen.getIndex(), neighbor)
        || !"boolean[]".equals(type(unit, visited))) return Optional.empty();
    List<Statement> steps = statements(guard.getThenStmt());
    if (steps.size() != 2
        || !mark(steps.get(0), visited, neighbor)
        || !(expression(steps.get(1)) instanceof MethodCallExpr enqueue)
        || !call(enqueue, queue, ENQUEUE, 1)
        || !ref(enqueue.getArgument(0), neighbor)) return Optional.empty();
    int index = parent.getStatements().indexOf(loop);
    if (index < 2
        || !(expression(parent.getStatement(index - 1)) instanceof MethodCallExpr seed)
        || !call(seed, queue, ENQUEUE, 1)) return Optional.empty();
    Expression source = unwrap(seed.getArgument(0));
    if (!(source instanceof NameExpr || source instanceof IntegerLiteralExpr)
        || !markSource(parent.getStatement(index - 2), visited, source)) return Optional.empty();
    if (source instanceof NameExpr name && !"int".equals(type(unit, name))) return Optional.empty();
    // Initialization must dominate both seed operations in this same block.
    VariableDeclarator v = declaration(unit, visited);
    if (v == null
        || !(v.getInitializer().orElse(null) instanceof ArrayCreationExpr array)
        || !array.getElementType().asString().equals("boolean")
        || array.getInitializer().isPresent()
        || q.findAncestor(BlockStmt.class).orElse(null) != parent
        || v.findAncestor(BlockStmt.class).orElse(null) != parent
        || q.getBegin()
            .orElseThrow()
            .isAfter(parent.getStatement(index - 2).getBegin().orElseThrow())
        || v.getBegin()
            .orElseThrow()
            .isAfter(parent.getStatement(index - 2).getBegin().orElseThrow()))
      return Optional.empty();
    Set<Node> allowed = Collections.newSetFromMap(new IdentityHashMap<>());
    allowed.add(empty);
    allowed.add(take);
    allowed.add(enqueue);
    allowed.add(seed);
    if (neighbors instanceof ForEachStmt each) allowed.add(each.getIterable());
    if (edge instanceof BinaryExpr comparison) allowed.add(unwrap(comparison.getLeft()));
    allowed.add(expression(steps.get(0)));
    allowed.add(expression(parent.getStatement(index - 2)));
    if (!safeUses(method, loop, queue, visited, graph, source, allowed)) return Optional.empty();
    Map<String, String> names = new LinkedHashMap<>();
    names.put("graph", graph.getNameAsString());
    names.put("queue", queue.getNameAsString());
    names.put("visited", visited.getNameAsString());
    names.put("current", current.getNameAsString());
    names.put("neighbor", neighbor.getNameAsString());
    Map<String, String> ids = new LinkedHashMap<>();
    ids.put("graph", id(graph));
    ids.put("queue", id(queue));
    ids.put("visited", id(visited));
    ids.put("current", AstAnalyzer.nodeId(current));
    ids.put("neighbor", AstAnalyzer.nodeId(neighbor));
    if (source instanceof NameExpr name) {
      ids.put("source", id(name));
      names.put("source", name.getNameAsString());
    }
    List<String> evidence =
        List.of(
            "Empty JDK FIFO is seeded after marking the source",
            "Each unseen adjacent vertex is marked before FIFO enqueue");
    Map<String, String> ops =
        Map.of(
            "seed",
            AstAnalyzer.nodeId(seed),
            "enqueue",
            AstAnalyzer.nodeId(enqueue),
            "dequeue",
            AstAnalyzer.nodeId(take),
            "mark",
            AstAnalyzer.nodeId(expression(steps.get(0))),
            "edge",
            AstAnalyzer.nodeId(edge == null ? ((ForEachStmt) neighbors).getIterable() : edge));
    BfsPattern pattern =
        new BfsPattern(
            1,
            representation,
            ids,
            ops,
            evidence,
            source instanceof IntegerLiteralExpr literal ? literal.asNumber().intValue() : null);
    var range = loop.getRange().orElseThrow();
    return Optional.of(
        new AlgorithmHint(
            "bfs",
            .95,
            "graph-bfs",
            evidence,
            names,
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
            pattern));
  }

  private static boolean safeUses(
      Node method,
      Node loop,
      NameExpr queue,
      NameExpr visited,
      NameExpr graph,
      Expression source,
      Set<Node> allowed) {
    for (NameExpr name : method.findAll(NameExpr.class)) {
      if (name.getBegin().orElseThrow().isAfter(loop.getEnd().orElseThrow())) continue;
      boolean protectedRef = same(name, queue) || same(name, visited) || same(name, graph);
      if (!protectedRef && !(source instanceof NameExpr src && same(name, src))) continue;
      Node parent = name.getParentNode().orElse(null);
      if (parent instanceof AssignExpr assign && assign.getTarget() == name) return false;
      if (parent instanceof UnaryExpr unary
          && unary.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return false;
      if (!protectedRef) continue;
      if (parent instanceof MethodCallExpr call) {
        if (call.getScope().orElse(null) != name) return false;
        if (same(name, queue) && !allowed.contains(call)) return false;
        if (same(name, graph)
            && !allowed.contains(call)
            && !(call.getNameAsString().equals("size")
                && call.getArguments().isEmpty()
                && call.getParentNode().orElse(null)
                    instanceof com.github.javaparser.ast.ArrayCreationLevel)) return false;
        if (same(name, visited)) return false;
      } else if (parent instanceof ArrayAccessExpr access && access.getName() == name) {
        Node root = access;
        while (root.getParentNode().orElse(null) instanceof ArrayAccessExpr)
          root = root.getParentNode().get();
        while (root.getParentNode().orElse(null) instanceof EnclosedExpr)
          root = root.getParentNode().get();
        if (same(name, graph) && !allowed.contains(root)) return false;
        if (root.getParentNode().orElse(null) instanceof AssignExpr assign
            && assign.getTarget() == root
            && !allowed.contains(assign)) return false;
        if (root.getParentNode().orElse(null) instanceof UnaryExpr unary
            && unary.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return false;
      } else if (!(parent instanceof FieldAccessExpr field
          && field.getScope() == name
          && field.getNameAsString().equals("length"))) return false;
    }
    return true;
  }

  private static boolean queueType(CompilationUnit unit, VariableDeclarator variable) {
    String raw = variable.getType().asString().replace(" ", "");
    int generic = raw.indexOf('<');
    if (generic < 0
        || !raw.substring(generic).equals("<Integer>")
        || !jdk(
            unit, raw.substring(0, generic), Set.of("Queue", "Deque", "ArrayDeque", "LinkedList")))
      return false;
    if (!(variable.getInitializer().orElse(null) instanceof ObjectCreationExpr create)
        || create.getAnonymousClassBody().isPresent()
        || !create.getArguments().isEmpty()) return false;
    String made = create.getType().getNameWithScope();
    return jdk(unit, made, Set.of("ArrayDeque", "LinkedList"))
        && (raw.startsWith("Queue<")
            || raw.startsWith("Deque<")
            || raw.startsWith("java.util.Queue<")
            || raw.startsWith("java.util.Deque<")
            || raw.substring(0, generic).equals(made));
  }

  private static boolean listType(CompilationUnit unit, String raw) {
    if (raw == null) return false;
    String text = raw.replace(" ", "");
    int at = text.indexOf('<');
    if (at < 0 || !jdk(unit, text.substring(0, at), Set.of("List", "ArrayList", "LinkedList")))
      return false;
    String inner = text.substring(at + 1, text.length() - 1);
    int nested = inner.indexOf('<');
    return nested > 0
        && inner.substring(nested).equals("<Integer>")
        && jdk(unit, inner.substring(0, nested), Set.of("List", "ArrayList", "LinkedList"));
  }

  private static boolean jdk(CompilationUnit unit, String name, Set<String> accepted) {
    String simple = name.substring(name.lastIndexOf('.') + 1);
    if (!accepted.contains(simple)) return false;
    if (unit.getImports().stream()
        .anyMatch(
            i ->
                i.isStatic()
                    && (i.isAsterisk()
                        || i.getName().getIdentifier().equals(simple)
                        || i.getName().getIdentifier().equals("Integer")))) return false;
    if (unit.findAll(TypeDeclaration.class).stream()
        .anyMatch(t -> t.getNameAsString().equals(simple) || t.getNameAsString().equals("Integer")))
      return false;
    if (unit.findAll(TypeParameter.class).stream()
        .anyMatch(t -> t.getNameAsString().equals(simple) || t.getNameAsString().equals("Integer")))
      return false;
    if (unit.getImports().stream()
        .anyMatch(
            i ->
                i.isAsterisk()
                    && !i.getNameAsString().equals("java.util")
                    && !i.getNameAsString().equals("java.lang"))) return false;
    if (name.equals("java.util." + simple)) return true;
    if (!name.equals(simple)) return false;
    if (unit.getImports().stream()
        .anyMatch(
            i ->
                !i.isStatic()
                    && !i.isAsterisk()
                    && (i.getName().getIdentifier().equals(simple)
                            && !i.getNameAsString().equals("java.util." + simple)
                        || i.getName().getIdentifier().equals("Integer")
                            && !i.getNameAsString().equals("java.lang.Integer")))) return false;
    return unit.getImports().stream()
        .anyMatch(
            i ->
                !i.isStatic()
                    && (i.getNameAsString().equals("java.util." + simple)
                        || i.isAsterisk() && i.getNameAsString().equals("java.util")));
  }

  private static VariableDeclarator declaration(CompilationUnit unit, NameExpr name) {
    String id = id(name);
    return unit.findAll(VariableDeclarator.class).stream()
        .filter(v -> AstAnalyzer.nodeId(v).equals(id))
        .findFirst()
        .orElse(null);
  }

  private static String type(CompilationUnit unit, NameExpr name) {
    String id = id(name);
    for (VariableDeclarator v : unit.findAll(VariableDeclarator.class))
      if (AstAnalyzer.nodeId(v).equals(id)) return v.getType().asString();
    for (Parameter p : unit.findAll(Parameter.class))
      if (AstAnalyzer.nodeId(p).equals(id)) return p.getType().asString();
    return null;
  }

  private static boolean mark(Statement statement, NameExpr visited, VariableDeclarator index) {
    return expression(statement) instanceof AssignExpr assign
        && assign.getOperator() == AssignExpr.Operator.ASSIGN
        && assign.getTarget() instanceof ArrayAccessExpr access
        && same(access.getName(), visited)
        && ref(access.getIndex(), index)
        && assign.getValue().isBooleanLiteralExpr()
        && assign.getValue().asBooleanLiteralExpr().getValue();
  }

  private static boolean markSource(Statement statement, NameExpr visited, Expression source) {
    return expression(statement) instanceof AssignExpr assign
        && assign.getOperator() == AssignExpr.Operator.ASSIGN
        && assign.getTarget() instanceof ArrayAccessExpr access
        && same(access.getName(), visited)
        && equalValue(access.getIndex(), source)
        && assign.getValue().isBooleanLiteralExpr()
        && assign.getValue().asBooleanLiteralExpr().getValue();
  }

  private static boolean equalValue(Expression a, Expression b) {
    return a instanceof NameExpr && b instanceof NameExpr
        ? same(a, b)
        : a instanceof IntegerLiteralExpr x
            && b instanceof IntegerLiteralExpr y
            && x.asNumber().equals(y.asNumber());
  }

  private static VariableDeclarator variable(Statement statement) {
    return expression(statement) instanceof VariableDeclarationExpr d
            && d.getVariables().size() == 1
        ? d.getVariable(0)
        : null;
  }

  private static Expression expression(Statement statement) {
    return statement instanceof ExpressionStmt e ? e.getExpression() : null;
  }

  private static List<Statement> statements(Statement s) {
    return s instanceof BlockStmt b ? b.getStatements() : List.of(s);
  }

  private static boolean call(MethodCallExpr c, NameExpr q, Set<String> names, int count) {
    return names.contains(c.getNameAsString())
        && c.getArguments().size() == count
        && same(c.getScope().orElse(null), q);
  }

  private static NameExpr scope(MethodCallExpr call) {
    return call.getScope().orElse(null) instanceof NameExpr n ? n : null;
  }

  private static String id(NameExpr name) {
    return LocalVariableBindings.resolve(name).orElse(null);
  }

  private static boolean same(Expression a, Expression b) {
    return a instanceof NameExpr x
        && b instanceof NameExpr y
        && id(x) != null
        && id(x).equals(id(y));
  }

  private static boolean ref(Expression e, VariableDeclarator v) {
    return unwrap(e) instanceof NameExpr n && AstAnalyzer.nodeId(v).equals(id(n));
  }

  private static boolean zero(Expression e) {
    return unwrap(e) instanceof IntegerLiteralExpr i && i.asNumber().intValue() == 0;
  }

  private static Expression unwrap(Expression e) {
    while (e instanceof EnclosedExpr enclosed) e = enclosed.getInner();
    return e;
  }
}
