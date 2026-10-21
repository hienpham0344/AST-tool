package com.example.astchunker.debug.operation;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.ObservationPoint;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Rewrites only a compilation copy; marker metadata always describes the untouched source AST. */
@Component
public class OperationInstrumenter {
  public OperationProgram instrument(String source, AstAnalyzer.Analysis original) {
    return new Rewrite(source, original).run();
  }

  private static final class Context {
    final List<String> variables;
    final List<String> scopes;
    final String returnType;
    String continueLabel;

    Context(List<String> variables, List<String> scopes, String returnType) {
      this.variables = new ArrayList<>(variables);
      this.scopes = new ArrayList<>(scopes);
      this.returnType = returnType;
    }

    Context copy() {
      Context copy = new Context(variables, scopes, returnType);
      copy.continueLabel = continueLabel;
      return copy;
    }
  }

  private static final class Rewrite {
    final String source;
    final AstAnalyzer.Analysis analysis;
    final Map<Integer, OperationSite> sites = new LinkedHashMap<>();
    final Set<String> names = new HashSet<>();
    final String helper;

    Rewrite(String source, AstAnalyzer.Analysis analysis) {
      this.source = source;
      this.analysis = analysis;
      analysis.compilationUnit().findAll(SimpleName.class).forEach(n -> names.add(n.asString()));
      helper = unique("__astTraceMarker");
    }

    String unique(String base) {
      String result = base;
      int n = 0;
      while (!names.add(result)) result = base + (++n);
      return result;
    }

    OperationProgram run() {
      var unit = analysis.compilationUnit();
      String simple =
          analysis.mainClassName().substring(analysis.mainClassName().lastIndexOf('.') + 1);
      ClassOrInterfaceDeclaration target = unit.getClassByName(simple).orElseThrow();
      validate(target);
      var copy = unit.clone();
      ClassOrInterfaceDeclaration changed = copy.getClassByName(simple).orElseThrow();
      var methods = target.getMethods();
      var changedMethods = changed.getMethods();
      var parser = new AstAnalyzer();
      for (int i = 0; i < methods.size(); i++) {
        var method = methods.get(i);
        if (method.getBody().isEmpty()) continue;
        List<String> parameters = method.getParameters().stream().map(AstAnalyzer::nodeId).toList();
        String body =
            block(
                method.getBody().get(),
                new Context(parameters, List.of(), method.getType().toString()));
        BlockStmt rewritten =
            parser
                .parseCompilationUnit("class __TraceParse { void body() " + body + " }")
                .getClassByName("__TraceParse")
                .orElseThrow()
                .getMethodsByName("body")
                .get(0)
                .getBody()
                .orElseThrow();
        changedMethods.get(i).setBody(rewritten);
      }
      changed.addMember(
          parser
              .parseCompilationUnit(
                  "class __TraceParse {private static boolean "
                      + helper
                      + "(int id, boolean value){return value;}}")
              .getClassByName("__TraceParse")
              .orElseThrow()
              .getMethods()
              .get(0)
              .clone());
      return new OperationProgram(
          copy.toString(), helper, Collections.unmodifiableMap(new LinkedHashMap<>(sites)));
    }

    void validate(ClassOrInterfaceDeclaration target) {
      List<String> problems = new ArrayList<>();
      if (analysis.compilationUnit().getTypes().size() != 1)
        problems.add(problem(target, "Multiple top-level types are unsupported"));
      target.walk(
          n -> {
            if (n != target
                && (n instanceof TypeDeclaration<?>
                    || n instanceof ConstructorDeclaration
                    || n instanceof InitializerDeclaration
                    || n instanceof LambdaExpr
                    || n instanceof ObjectCreationExpr
                    || n instanceof TryStmt
                    || n instanceof SwitchStmt
                    || n instanceof SwitchExpr
                    || n instanceof LabeledStmt
                    || n instanceof SynchronizedStmt
                    || n instanceof ThrowStmt
                    || n instanceof AssertStmt
                    || n instanceof YieldStmt
                    || n instanceof MethodReferenceExpr
                    || n instanceof InstanceOfExpr))
              problems.add(problem(n, "Unsupported " + n.getClass().getSimpleName()));
            if (n instanceof MethodDeclaration method
                && (method.isSynchronized() || method.getBody().isEmpty()))
              problems.add(problem(n, "Methods require an ordinary non-synchronized body"));
            if (n instanceof VariableDeclarator v
                && v.findAncestor(FieldDeclaration.class).isPresent()
                && v.getInitializer().isPresent()
                && !(v.getInitializer().get() instanceof LiteralExpr))
              problems.add(problem(n, "Executable field initializers are unsupported"));
          });
      if (!problems.isEmpty()) throw new OperationCoverageException(problems);
    }

    String problem(Node n, String message) {
      return n.getRange()
              .map(r -> r.begin.line + ":" + r.begin.column + "-" + r.end.line + ":" + r.end.column)
              .orElse("unknown")
          + ": "
          + message;
    }

    ObservationPoint point(Node n) {
      var r = n.getRange().orElseThrow();
      String[] lines = source.split("\\R", -1);
      String code;
      if (r.begin.line == r.end.line)
        code = lines[r.begin.line - 1].substring(r.begin.column - 1, r.end.column);
      else {
        StringBuilder text =
            new StringBuilder(lines[r.begin.line - 1].substring(r.begin.column - 1));
        for (int i = r.begin.line; i < r.end.line - 1; i++) text.append('\n').append(lines[i]);
        code = text.append('\n').append(lines[r.end.line - 1], 0, r.end.column).toString();
      }
      return new ObservationPoint(
          AstAnalyzer.nodeId(n),
          analysis.mainClassName(),
          r.begin.line,
          r.begin.line,
          r.end.line,
          r.begin.column,
          r.end.column,
          n.getClass().getSimpleName(),
          code);
    }

    String marker(
        Node n, String phase, String kind, Context c, Map<String, String> attrs, String value) {
      int id = sites.size() + 1;
      sites.put(
          id,
          new OperationSite(
              id,
              AstAnalyzer.nodeId(n) + ":" + kind,
              phase,
              kind,
              point(n),
              List.copyOf(c.variables),
              List.copyOf(c.scopes),
              Map.copyOf(attrs)));
      return helper + "(" + id + ", " + value + ")";
    }

    String event(Node n, String phase, String kind, Context c, Map<String, String> attrs) {
      return marker(n, phase, kind, c, attrs, "true") + ";\n";
    }

    String before(Node n, String kind, Context c, Map<String, String> attrs) {
      return event(n, "BEFORE", kind, c, attrs);
    }

    String after(Node n, String kind, Context c, Map<String, String> attrs) {
      return event(n, "AFTER", kind, c, attrs);
    }

    String block(BlockStmt b, Context parent) {
      Context c = parent.copy();
      c.scopes.add(AstAnalyzer.nodeId(b));
      Map<String, String> a = Map.of("scopeId", AstAnalyzer.nodeId(b));
      String enter = event(b, "ENTER", "SCOPE", c, a);
      StringBuilder body = new StringBuilder();
      for (Statement s : b.getStatements()) body.append(statement(s, c));
      return "{\n"
          + enter
          + "try {\n"
          + body
          + "} finally {\n"
          + event(b, "EXIT", "SCOPE", c, a)
          + "}\n}";
    }

    String controlled(Statement s, Context c) {
      return s instanceof BlockStmt b ? block(b, c) : "{\n" + statement(s, c.copy()) + "}\n";
    }

    Map<String, String> loop(Node n) {
      return Map.of("loopId", AstAnalyzer.nodeId(n));
    }

    String condition(Expression e, String kind, Context c, Map<String, String> a) {
      return marker(
          e, "AFTER", kind, c, a, marker(e, "BEFORE", kind, c, a, "true") + " & (" + e + ")");
    }

    boolean constantBoolean(Expression expression) {
      return constantExpression(expression, new HashSet<>());
    }

    boolean constantExpression(Expression expression, Set<Node> visiting) {
      if (!visiting.add(expression)) return false;
      try {
        if (expression instanceof LiteralExpr) return !(expression instanceof NullLiteralExpr);
        if (expression instanceof EnclosedExpr e) return constantExpression(e.getInner(), visiting);
        if (expression instanceof CastExpr e)
          return (e.getType().isPrimitiveType() || e.getType().asString().equals("String"))
              && constantExpression(e.getExpression(), visiting);
        if (expression instanceof UnaryExpr e)
          return switch (e.getOperator()) {
            case PLUS, MINUS, LOGICAL_COMPLEMENT, BITWISE_COMPLEMENT ->
                constantExpression(e.getExpression(), visiting);
            default -> false;
          };
        if (expression instanceof BinaryExpr e)
          return constantExpression(e.getLeft(), visiting)
              && constantExpression(e.getRight(), visiting);
        if (expression instanceof ConditionalExpr e)
          return constantExpression(e.getCondition(), visiting)
              && constantExpression(e.getThenExpr(), visiting)
              && constantExpression(e.getElseExpr(), visiting);
        String name = null;
        if (expression instanceof NameExpr e) name = e.getNameAsString();
        if (expression instanceof FieldAccessExpr e
            && e.getScope() instanceof NameExpr scope
            && scope
                .getNameAsString()
                .equals(analysis.compilationUnit().getTypes().get(0).getNameAsString()))
          name = e.getNameAsString();
        if (name == null) return false;
        VariableDeclarator found = null;
        int closest = -1;
        for (VariableDeclarator declaration :
            analysis.compilationUnit().findAll(VariableDeclarator.class)) {
          if (!declaration.getNameAsString().equals(name)) continue;
          Node owner = declaration.getParentNode().orElseThrow();
          if (expression instanceof FieldAccessExpr && !(owner instanceof FieldDeclaration))
            continue;
          Node scope =
              owner instanceof FieldDeclaration
                  ? analysis.compilationUnit().getTypes().get(0)
                  : declaration
                      .findAncestor(ForStmt.class)
                      .filter(
                          f ->
                              f.getInitialization().stream()
                                  .anyMatch(init -> init.isAncestorOf(declaration)))
                      .map(f -> (Node) f)
                      .orElseGet(() -> declaration.findAncestor(BlockStmt.class).orElseThrow());
          if (!scope.isAncestorOf(expression)) continue;
          if (!(owner instanceof FieldDeclaration)
              && declaration.getBegin().orElseThrow().isAfter(expression.getBegin().orElseThrow()))
            continue;
          int depth = 0;
          for (Node n = scope; n.getParentNode().isPresent(); n = n.getParentNode().get()) depth++;
          if (depth >= closest) {
            found = declaration;
            closest = depth;
          }
        }
        if (found == null) return false;
        if (!(found.getType().isPrimitiveType()
            || found.getType().isVarType()
            || found.getType().asString().equals("String")
            || found.getType().asString().equals("java.lang.String"))) return false;
        if (expression instanceof NameExpr
            && found.getParentNode().orElseThrow() instanceof FieldDeclaration) {
          String variableName = name;
          if (expression.findAncestor(MethodDeclaration.class).stream()
              .flatMap(m -> m.getParameters().stream())
              .anyMatch(parameter -> parameter.getNameAsString().equals(variableName)))
            return false;
        }
        Node owner = found.getParentNode().orElseThrow();
        boolean isFinal =
            owner instanceof VariableDeclarationExpr v && v.isFinal()
                || owner instanceof FieldDeclaration f && f.isFinal();
        return isFinal
            && found.getInitializer().isPresent()
            && constantExpression(found.getInitializer().get(), visiting);
      } finally {
        visiting.remove(expression);
      }
    }

    boolean normalCompletion(Statement statement) {
      if (statement instanceof ReturnStmt
          || statement instanceof BreakStmt
          || statement instanceof ContinueStmt) return false;
      if (statement instanceof BlockStmt block)
        return block.getStatements().isEmpty()
            || normalCompletion(block.getStatement(block.getStatements().size() - 1));
      if (statement instanceof IfStmt branch)
        return branch.getElseStmt().isEmpty()
            || normalCompletion(branch.getThenStmt())
            || normalCompletion(branch.getElseStmt().get());
      if (statement instanceof WhileStmt loop)
        return !constantBoolean(loop.getCondition()) || hasTransfer(loop, BreakStmt.class);
      if (statement instanceof ForStmt loop)
        return loop.getCompare().isPresent() && !constantBoolean(loop.getCompare().get())
            || hasTransfer(loop, BreakStmt.class);
      if (statement instanceof DoStmt loop) {
        if (hasTransfer(loop, BreakStmt.class)) return true;
        if (!normalCompletion(loop.getBody()) && !hasTransfer(loop, ContinueStmt.class))
          return false;
        if (!constantBoolean(loop.getCondition())) return true;
        Expression condition = loop.getCondition();
        while (condition instanceof EnclosedExpr enclosed) condition = enclosed.getInner();
        if (condition instanceof BooleanLiteralExpr literal) return !literal.getValue();
        throw new OperationCoverageException(
            List.of(
                problem(
                    loop.getCondition(),
                    "Nested constant do-while completion requires a boolean literal")));
      }
      return true;
    }

    boolean hasTransfer(Node loopNode, Class<? extends Statement> type) {
      return loopNode.findAll(type).stream()
          .anyMatch(
              transfer -> {
                Node parent = transfer.getParentNode().orElse(null);
                while (parent != null
                    && !(parent instanceof ForStmt
                        || parent instanceof ForEachStmt
                        || parent instanceof WhileStmt
                        || parent instanceof DoStmt)) parent = parent.getParentNode().orElse(null);
                return parent == loopNode;
              });
    }

    String doLoop(DoStmt loop, Context parent) {
      return loopScope(
          loop,
          parent,
          inner -> {
            if (!constantBoolean(loop.getCondition()))
              return "do "
                  + controlled(loop.getBody(), inner)
                  + "while("
                  + condition(loop.getCondition(), "LOOP_CONDITION", inner, loop(loop))
                  + ");";
            String label = unique("__astTraceContinue");
            inner.continueLabel = label;
            String body = label + ": " + controlled(loop.getBody(), inner);
            String epilogue =
                normalCompletion(loop.getBody()) || hasTransfer(loop, ContinueStmt.class)
                    ? before(loop.getCondition(), "LOOP_CONDITION", inner, loop(loop))
                        + eventValue(
                            loop.getCondition(),
                            "LOOP_CONDITION",
                            inner,
                            loop(loop),
                            loop.getCondition().toString())
                    : "";
            return "do {" + body + epilogue + "}while(" + loop.getCondition() + ");";
          });
    }

    String statement(Statement s, Context c) {
      if (s instanceof BlockStmt b) return block(b, c);
      if (s instanceof EmptyStmt) return ";\n";
      if (s instanceof ExpressionStmt e) {
        if (e.getExpression() instanceof VariableDeclarationExpr d)
          return declarations(d, c, "DECLARATION", Map.of());
        return operation(e, e.getExpression(), c, kind(e.getExpression()), Map.of());
      }
      if (s instanceof IfStmt i) {
        String prefix = "";
        String test;
        if (constantBoolean(i.getCondition())) {
          prefix =
              before(i.getCondition(), "CONDITION", c, Map.of())
                  + eventValue(
                      i.getCondition(), "CONDITION", c, Map.of(), i.getCondition().toString());
          test = i.getCondition().toString();
        } else test = condition(i.getCondition(), "CONDITION", c, Map.of());
        return prefix
            + "if ("
            + test
            + ")"
            + controlled(i.getThenStmt(), c)
            + i.getElseStmt().map(e -> "else " + controlled(e, c)).orElse("")
            + "\n";
      }
      if (s instanceof ForStmt f) return forLoop(f, c);
      if (s instanceof ForEachStmt f) return forEach(f, c);
      if (s instanceof WhileStmt w)
        return loopScope(
            w,
            c,
            inner -> {
              String test = w.getCondition().toString();
              String pre = "";
              if (constantBoolean(w.getCondition()))
                pre =
                    before(w.getCondition(), "LOOP_CONDITION", inner, loop(w))
                        + eventValue(w.getCondition(), "LOOP_CONDITION", inner, loop(w), test);
              else test = condition(w.getCondition(), "LOOP_CONDITION", inner, loop(w));
              return "while(" + test + "){" + pre + controlled(w.getBody(), inner) + "}";
            });
      if (s instanceof DoStmt d) return doLoop(d, c);
      if (s instanceof ReturnStmt r) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("completion", "RETURN_REQUEST_EVALUATED");
        String code = before(r, "RETURN", c, a);
        if (r.getExpression().isPresent()) {
          String temp = unique("__astTraceReturn");
          a.put("returnTemporary", temp);
          a.put("returnType", c.returnType);
          return code
              + c.returnType
              + " "
              + temp
              + " = "
              + r.getExpression().get()
              + ";\n"
              + after(r, "RETURN", c, a)
              + "return "
              + temp
              + ";\n";
        }
        return code + after(r, "RETURN", c, a) + "return;\n";
      }
      if (s instanceof BreakStmt || s instanceof ContinueStmt) {
        String kind = s instanceof BreakStmt ? "BREAK" : "CONTINUE";
        Map<String, String> a = Map.of("completion", "TRANSFER_REQUESTED");
        return before(s, kind, c, a)
            + after(s, kind, c, a)
            + (s instanceof ContinueStmt && c.continueLabel != null
                ? "break " + c.continueLabel + ";"
                : s.toString())
            + "\n";
      }
      throw new OperationCoverageException(
          List.of(problem(s, "Unsupported statement " + s.getClass().getSimpleName())));
    }

    String eventValue(Node n, String kind, Context c, Map<String, String> a, String value) {
      return marker(n, "AFTER", kind, c, a, value) + ";\n";
    }

    String kind(Expression e) {
      if (e instanceof AssignExpr a)
        return a.getTarget().isArrayAccessExpr() ? "ARRAY_WRITE" : "ASSIGNMENT";
      if (e instanceof UnaryExpr u
          && (u.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
              || u.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT
              || u.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT
              || u.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT)) return "INCREMENT";
      if (e instanceof MethodCallExpr) return "METHOD_CALL";
      return "EXPRESSION";
    }

    String operation(
        Node source, Expression expression, Context c, String kind, Map<String, String> a) {
      return before(source, kind, c, a) + expression + ";\n" + after(source, kind, c, a);
    }

    String declarations(
        VariableDeclarationExpr d, Context c, String kind, Map<String, String> extras) {
      StringBuilder result = new StringBuilder();
      for (VariableDeclarator v : d.getVariables()) {
        Map<String, String> a = new LinkedHashMap<>(extras);
        a.put("declarationId", AstAnalyzer.nodeId(v));
        a.put("hasInitializer", Boolean.toString(v.getInitializer().isPresent()));
        result.append(before(v, kind, c, a));
        VariableDeclarationExpr single = d.clone();
        single.setVariables(new com.github.javaparser.ast.NodeList<>(v.clone()));
        result.append(single).append(";\n");
        c.variables.add(AstAnalyzer.nodeId(v));
        result.append(after(v, kind, c, a));
      }
      return result.toString();
    }

    String loopScope(
        Node loopNode, Context parent, java.util.function.Function<Context, String> body) {
      Context inner = parent.copy();
      inner.continueLabel = null;
      inner.scopes.add(AstAnalyzer.nodeId(loopNode));
      Map<String, String> a =
          Map.of("scopeId", AstAnalyzer.nodeId(loopNode), "loopId", AstAnalyzer.nodeId(loopNode));
      String entry = event(loopNode, "ENTER", "LOOP", inner, a);
      String rendered = body.apply(inner);
      return "{\n"
          + entry
          + "try {\n"
          + rendered
          + "\n}finally{\n"
          + event(loopNode, "EXIT", "LOOP", inner, a)
          + "}\n}\n";
    }

    String forLoop(ForStmt f, Context c) {
      return loopScope(
          f,
          c,
          inner -> {
            StringBuilder init = new StringBuilder();
            for (Expression e : f.getInitialization()) {
              if (e instanceof VariableDeclarationExpr d)
                init.append(declarations(d, inner, "LOOP_INIT", loop(f)));
              else init.append(operation(e, e, inner, "LOOP_INIT", loop(f)));
            }
            String test = "";
            String prefix = "";
            if (f.getCompare().isPresent()) {
              Expression e = f.getCompare().get();
              test = e.toString();
              if (constantBoolean(e))
                prefix =
                    before(e, "LOOP_CONDITION", inner, loop(f))
                        + eventValue(e, "LOOP_CONDITION", inner, loop(f), test);
              else test = condition(e, "LOOP_CONDITION", inner, loop(f));
            }
            List<String> updates = new ArrayList<>();
            for (Expression e : f.getUpdate()) {
              updates.add(marker(e, "BEFORE", "LOOP_UPDATE", inner, loop(f), "true"));
              updates.add(e.toString());
              updates.add(marker(e, "AFTER", "LOOP_UPDATE", inner, loop(f), "true"));
            }
            return init
                + "for(;"
                + test
                + ";"
                + String.join(",", updates)
                + "){\n"
                + prefix
                + controlled(f.getBody(), inner)
                + "}";
          });
    }

    String forEach(ForEachStmt f, Context c) {
      return loopScope(
          f,
          c,
          inner -> {
            VariableDeclarator v = f.getVariable().getVariable(0);
            String temp = unique("__astTraceIterable");
            String iterable =
                before(f.getIterable(), "LOOP_INIT", inner, loop(f))
                    + "var "
                    + temp
                    + " = "
                    + f.getIterable()
                    + ";\n"
                    + after(f.getIterable(), "LOOP_INIT", inner, loop(f));
            Context binding = inner.copy();
            String bindingScope = AstAnalyzer.nodeId(v);
            binding.scopes.add(bindingScope);
            binding.variables.add(bindingScope);
            Map<String, String> a = new LinkedHashMap<>(loop(f));
            a.put("declarationId", bindingScope);
            a.put("scopeId", bindingScope);
            a.put("hasInitializer", "true");
            a.put("beforeUnavailable", "ENHANCED_FOR_BINDING");
            Map<String, String> scope = Map.of("scopeId", bindingScope);
            return iterable
                + "for("
                + f.getVariable()
                + " : "
                + temp
                + "){\n"
                + event(v, "ENTER", "SCOPE", binding, scope)
                + "try {"
                + after(v, "LOOP_BINDING", binding, a)
                + controlled(f.getBody(), binding)
                + "}finally{"
                + event(v, "EXIT", "SCOPE", binding, scope)
                + "}}";
          });
    }
  }
}
