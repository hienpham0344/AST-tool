package com.example.astchunker.algorithm.sort;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Immutable, declaration-aware facts used by conservative sort detectors. */
final class SortSemanticFacts {
  private SortSemanticFacts() {}

  static Analysis analyze(CompilationUnit unit) {
    if (unit == null) throw new IllegalArgumentException("Compilation unit is required");
    return new Analysis(unit);
  }

  static final class Analysis {
    private final Map<String, SequenceBinding> bindings;
    private final Map<String, String> types;
    private final Map<String, ComparatorBinding> comparators;

    Optional<NormalizedComparison> strictComparison(Expression expression) {
      Expression unwrapped = unwrap(expression);
      if (!(unwrapped instanceof BinaryExpr binary)
          || binary.getOperator() != BinaryExpr.Operator.LESS && binary.getOperator() != BinaryExpr.Operator.GREATER)
        return Optional.empty();
      Expression left = unwrap(binary.getLeft()), right = unwrap(binary.getRight());
      if (!scalar(left) || !scalar(right)) return Optional.empty();
      // Canonical greater-than reverses operands and relation together, without rewriting nodes.
      if (binary.getOperator() == BinaryExpr.Operator.LESS) { Expression swap = left; left = right; right = swap; }
      List<String> ids = binary.findAll(NameExpr.class).stream().map(LocalVariableBindings::resolve)
          .flatMap(Optional::stream).distinct().toList();
      return Optional.of(new NormalizedComparison(AstAnalyzer.nodeId(expression), left, BinaryExpr.Operator.GREATER, right, ids));
    }

    /** Recognizes a direct fully-qualified Comparator compare result against literal zero. */
    Optional<NormalizedComparatorComparison> strictComparatorComparison(Expression expression) {
      Expression unwrapped = unwrap(expression);
      if (!(unwrapped instanceof BinaryExpr binary)
          || binary.getOperator() != BinaryExpr.Operator.LESS
              && binary.getOperator() != BinaryExpr.Operator.GREATER) return Optional.empty();
      Expression left = unwrap(binary.getLeft());
      Expression right = unwrap(binary.getRight());
      MethodCallExpr call;
      BinaryExpr.Operator operator = binary.getOperator();
      if (isZero(right) && left instanceof MethodCallExpr leftCall) {
        call = leftCall;
      } else if (isZero(left) && right instanceof MethodCallExpr rightCall) {
        call = rightCall;
        operator = operator == BinaryExpr.Operator.GREATER
            ? BinaryExpr.Operator.LESS : BinaryExpr.Operator.GREATER;
      } else {
        return Optional.empty();
      }
      if (!call.getNameAsString().equals("compare") || call.getArguments().size() != 2
          || call.getTypeArguments().isPresent()) return Optional.empty();
      Expression receiver = call.getScope().map(SortSemanticFacts::unwrap).orElse(null);
      if (!(receiver instanceof NameExpr name)) return Optional.empty();
      ComparatorBinding comparator = LocalVariableBindings.resolve(name).map(comparators::get).orElse(null);
      if (comparator == null) return Optional.empty();
      List<String> ids = binary.findAll(NameExpr.class).stream().map(LocalVariableBindings::resolve)
          .flatMap(Optional::stream).distinct().toList();
      return Optional.of(new NormalizedComparatorComparison(AstAnalyzer.nodeId(expression), AstAnalyzer.nodeId(call),
          comparator, call.getArgument(0), operator, call.getArgument(1), ids));
    }

    Optional<IndexOperand> indexOperand(Expression expression) {
      Expression value = unwrap(expression);
      int offset = 0;
      if (value instanceof BinaryExpr binary) {
        if (!one(binary.getRight())) return Optional.empty();
        if (binary.getOperator() == BinaryExpr.Operator.PLUS) offset = 1;
        else if (binary.getOperator() == BinaryExpr.Operator.MINUS) offset = -1;
        else return Optional.empty();
        value = unwrap(binary.getLeft());
      }
      if (!(value instanceof NameExpr name)) return Optional.empty();
      String id = LocalVariableBindings.resolve(name).orElse(null);
      return id != null && "int".equals(types.get(id))
          ? Optional.of(new IndexOperand(AstAnalyzer.nodeId(expression), id, name.getNameAsString(), offset)) : Optional.empty();
    }

    Optional<Counter> forwardUnitCounter(ForStmt loop) { return counter(loop, 1); }
    Optional<Counter> backwardUnitCounter(ForStmt loop) { return counter(loop, -1); }

    private Optional<Counter> counter(ForStmt loop, int step) {
      if (loop.getInitialization().size() != 1 || loop.getUpdate().size() != 1
          || !(loop.getInitialization().get(0) instanceof VariableDeclarationExpr declaration)
          || declaration.getVariables().size() != 1) return Optional.empty();
      VariableDeclarator variable = declaration.getVariable(0);
      if (!"int".equals(variable.getType().asString()) || variable.getInitializer().isEmpty()) return Optional.empty();
      String id = AstAnalyzer.nodeId(variable);
      Expression update = unwrap(loop.getUpdate().get(0));
      Expression target = null;
      if (update instanceof UnaryExpr unary) {
        boolean valid = step == 1
            ? unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT || unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
            : unary.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT || unary.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT;
        if (valid) target = unary.getExpression();
      } else if (update instanceof AssignExpr assign) {
        if (assign.getOperator() == (step == 1 ? AssignExpr.Operator.PLUS : AssignExpr.Operator.MINUS) && one(assign.getValue()))
          target = assign.getTarget();
        else if (assign.getOperator() == AssignExpr.Operator.ASSIGN) {
          Optional<IndexOperand> rhs = indexOperand(assign.getValue());
          if (rhs.isPresent() && rhs.get().declarationId().equals(id) && rhs.get().offset() == step) target = assign.getTarget();
        }
      }
      Optional<IndexOperand> operand = target == null ? Optional.empty() : indexOperand(target);
      if (operand.isEmpty() || operand.get().offset() != 0 || !operand.get().declarationId().equals(id)) return Optional.empty();
      return Optional.of(new Counter(AstAnalyzer.nodeId(loop), id, variable.getNameAsString(), step,
          variable.getInitializer().get(), AstAnalyzer.nodeId(update)));
    }

    Optional<LoopBound> supportedLengthBound(ForStmt loop, SequenceBinding sequence) {
      Expression condition = unwrap(loop.getCompare().orElse(null));
      if (!(condition instanceof BinaryExpr binary)) return Optional.empty();
      Expression index, bound;
      if (binary.getOperator() == BinaryExpr.Operator.LESS) { index = binary.getLeft(); bound = binary.getRight(); }
      else if (binary.getOperator() == BinaryExpr.Operator.GREATER) { index = binary.getRight(); bound = binary.getLeft(); }
      else return Optional.empty();
      Optional<IndexOperand> operand = indexOperand(index);
      Optional<Counter> counter = forwardUnitCounter(loop);
      if (operand.isEmpty() || operand.get().offset() != 0 || counter.isEmpty()
          || !operand.get().declarationId().equals(counter.get().declarationId())) return Optional.empty();
      bound = unwrap(bound);
      Optional<IndexOperand> pass = Optional.empty();
      int offset = 0;
      if (bound instanceof BinaryExpr subtraction && subtraction.getOperator() == BinaryExpr.Operator.MINUS) {
        if (one(subtraction.getRight())) { offset = -1; bound = unwrap(subtraction.getLeft()); }
        else {
          pass = indexOperand(subtraction.getRight());
          if (pass.isEmpty() || pass.get().offset() != 0 || pass.get().declarationId().equals(operand.get().declarationId())) return Optional.empty();
          Expression prefix = unwrap(subtraction.getLeft());
          if (!(prefix instanceof BinaryExpr minusOne) || minusOne.getOperator() != BinaryExpr.Operator.MINUS || !one(minusOne.getRight())) return Optional.empty();
          offset = -1; bound = unwrap(minusOne.getLeft());
        }
      }
      if (!(bound instanceof FieldAccessExpr length) || !length.getNameAsString().equals("length")) return Optional.empty();
      Optional<SequenceAccess> receiver = sequenceAccess(length.getScope());
      if (receiver.isEmpty() || !receiver.get().binding().canonicalDeclarationId().equals(sequence.canonicalDeclarationId())) return Optional.empty();
      return Optional.of(new LoopBound(AstAnalyzer.nodeId(condition), operand.get(), receiver.get().binding(), offset, pass));
    }

    boolean hasInterferingMutation(Node scope, SequenceBinding sequence, Set<String> allowedAstNodeIds) {
      for (Node node : scope.findAll(Node.class)) {
        if (allowed(node, scope, allowedAstNodeIds)) continue;
        if (node instanceof MethodCallExpr || node instanceof ObjectCreationExpr) return true;
        if (node instanceof AssignExpr assign) {
          Expression target = unwrap(assign.getTarget());
          if (target instanceof ArrayAccessExpr access) {
            Optional<SequenceAccess> binding = sequenceAccess(access);
            if (binding.isEmpty() || binding.get().binding().canonicalDeclarationId().equals(sequence.canonicalDeclarationId())) return true;
          } else return true;
        }
        if (node instanceof UnaryExpr unary && switch (unary.getOperator()) {
          case PREFIX_INCREMENT, POSTFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_DECREMENT -> true;
          default -> false;
        }) return true;
      }
      return false;
    }

    private boolean scalar(Expression expression) {
      Expression value = unwrap(expression);
      if (value instanceof IntegerLiteralExpr) return true;
      if (value instanceof NameExpr name) {
        String id = LocalVariableBindings.resolve(name).orElse(null);
        return id != null && isNumericPrimitive(types.get(id));
      }
      if (value instanceof ArrayAccessExpr access) return sequenceAccess(access).filter(a -> a.binding().isNumericPrimitiveArray()).isPresent()
          && (indexOperand(access.getIndex()).isPresent() || unwrap(access.getIndex()) instanceof IntegerLiteralExpr);
      return false;
    }

    private Analysis(CompilationUnit unit) {
      Map<String, SequenceBinding> known = new LinkedHashMap<>();
      Map<String, String> declaredTypes = new LinkedHashMap<>();
      Set<String> invalidDeclarations = new HashSet<>();
      // A local rebind anywhere in the callable makes later alias inference
      // flow-dependent. Keep the source binding, but do not infer new aliases.
      for (AssignExpr assign : unit.findAll(AssignExpr.class)) {
        if (unwrap(assign.getTarget()) instanceof NameExpr name)
          LocalVariableBindings.resolve(name).ifPresent(invalidDeclarations::add);
      }
      for (Parameter parameter : unit.findAll(Parameter.class)) {
        if (LocalVariableBindings.callable(parameter) == null) continue;
        declaredTypes.put(AstAnalyzer.nodeId(parameter), parameter.getType().asString());
        if (!isArray(parameter.getType().asString())) continue;
        String id = AstAnalyzer.nodeId(parameter);
        String arrayType = parameter.getType().asString();
        known.put(id, new SequenceBinding(id, id, parameter.getNameAsString(), kind(arrayType), arrayType, elementType(arrayType)));
      }
      for (VariableDeclarator variable : unit.findAll(VariableDeclarator.class)) {
        if (LocalVariableBindings.callable(variable) == null) continue;
        declaredTypes.put(AstAnalyzer.nodeId(variable), variable.getType().asString());
        if (!isArray(variable.getType().asString())) continue;
        String id = AstAnalyzer.nodeId(variable);
        String arrayType = variable.getType().asString();
        SequenceBinding direct = new SequenceBinding(id, id, variable.getNameAsString(), kind(arrayType), arrayType, elementType(arrayType));
        SequenceBinding binding = direct;
        Expression initial = unwrap(variable.getInitializer().orElse(null));
        if (initial instanceof NameExpr initializer) {
          SequenceBinding source = LocalVariableBindings.resolve(initializer).map(known::get).orElse(null);
          if (source == null || !source.arrayType().equals(direct.arrayType())
              || invalidDeclarations.contains(source.sourceDeclarationId())) continue;
          binding = new SequenceBinding(id, source.canonicalDeclarationId(), variable.getNameAsString(), source.kind(), source.arrayType(), source.elementType());
        } else if (!(initial instanceof ArrayCreationExpr || initial instanceof ArrayInitializerExpr)) continue;
        known.put(id, binding);
      }
      Map<String, SequenceBinding> activeBindings = new LinkedHashMap<>(known);
      this.bindings = java.util.Collections.unmodifiableMap(activeBindings);
      this.types = Map.copyOf(declaredTypes);
      Map<String, ComparatorBinding> directComparators = new LinkedHashMap<>();
      for (Parameter parameter : unit.findAll(Parameter.class)) {
        if (LocalVariableBindings.callable(parameter) == null) continue;
        comparatorElementType(parameter.getType().asString()).ifPresent(elementType ->
            directComparators.put(AstAnalyzer.nodeId(parameter),
                new ComparatorBinding(AstAnalyzer.nodeId(parameter), parameter.getNameAsString(), elementType)));
      }
      this.comparators = Map.copyOf(directComparators);
      SameFileHelperSummaries summaries = SameFileHelperSummaries.analyze(unit, this);
      Set<String> escapedDeclarations = new HashSet<>();
      for (AssignExpr assign : unit.findAll(AssignExpr.class)) {
        // An assignment may publish a receiver even through a nested expression.
        recordEscapes(assign.getValue(), known, escapedDeclarations);
      }
      for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
        if (summaries.resolveExchange(call).status() == SameFileHelperSummaries.Status.RESOLVED
            || summaries.resolveComparison(call).status() == SameFileHelperSummaries.Status.RESOLVED) continue;
        for (Expression argument : call.getArguments()) recordEscapes(argument, known, escapedDeclarations);
      }
      for (ObjectCreationExpr construction : unit.findAll(ObjectCreationExpr.class)) {
        for (Expression argument : construction.getArguments()) recordEscapes(argument, known, escapedDeclarations);
      }
      for (ReturnStmt returned : unit.findAll(ReturnStmt.class)) {
        recordEscapes(returned.getExpression().orElse(null), known, escapedDeclarations);
      }
      for (VariableDeclarator variable : unit.findAll(VariableDeclarator.class)) {
        Expression initializer = variable.getInitializer().orElse(null);
        if (initializer == null) continue;
        SequenceBinding binding = known.get(AstAnalyzer.nodeId(variable));
        Expression source = unwrap(initializer);
        boolean trackedAlias = binding != null
            && !binding.sourceDeclarationId().equals(binding.canonicalDeclarationId())
            && source instanceof NameExpr name
            && LocalVariableBindings.resolve(name).map(known::get)
                .filter(original -> original.canonicalDeclarationId().equals(binding.canonicalDeclarationId())).isPresent();
        if (!trackedAlias) recordEscapes(initializer, known, escapedDeclarations);
      }
      activeBindings.entrySet().removeIf(entry -> invalidDeclarations.contains(entry.getKey())
          || !entry.getValue().sourceDeclarationId().equals(entry.getValue().canonicalDeclarationId())
              && (escapedDeclarations.contains(entry.getKey())
                  || escapedDeclarations.contains(entry.getValue().canonicalDeclarationId())));
    }

    Optional<SequenceAccess> sequenceAccess(Expression expression) {
      Expression receiver = unwrap(expression);
      if (receiver instanceof ArrayAccessExpr access) receiver = unwrap(access.getName());
      if (!(receiver instanceof NameExpr name)) return Optional.empty();
      return LocalVariableBindings.resolve(name).map(bindings::get).filter(binding -> binding != null)
          .map(binding -> new SequenceAccess(AstAnalyzer.nodeId(expression), binding));
    }

    Optional<String> scalarType(Expression expression) {
      Expression value = unwrap(expression);
      if (!(value instanceof NameExpr name)) return Optional.empty();
      return LocalVariableBindings.resolve(name).map(types::get).filter(SortSemanticFacts::isNumericPrimitive);
    }
  }

  enum SequenceKind { INT_ARRAY, NUMERIC_PRIMITIVE_ARRAY, BOOLEAN_ARRAY, OBJECT_ARRAY, UNKNOWN }

  record SequenceBinding(String sourceDeclarationId, String canonicalDeclarationId, String displayName, SequenceKind kind,
      String arrayType, String elementType) {
    SequenceBinding {
      if (sourceDeclarationId == null || canonicalDeclarationId == null || displayName == null || kind == null
          || arrayType == null || elementType == null)
        throw new IllegalArgumentException("Sequence binding fields are required");
    }

    boolean isNumericPrimitiveArray() {
      return kind == SequenceKind.INT_ARRAY || kind == SequenceKind.NUMERIC_PRIMITIVE_ARRAY;
    }
  }
  record ComparatorBinding(String declarationId, String displayName, String elementType) {
    ComparatorBinding {
      if (declarationId == null || displayName == null || elementType == null || elementType.isEmpty())
        throw new IllegalArgumentException("Comparator binding fields are required");
    }

    boolean supports(SequenceBinding sequence) {
      return sequence != null && sequence.kind() == SequenceKind.OBJECT_ARRAY
          && !sequence.elementType().endsWith("[]") && elementType.equals(sequence.elementType());
    }
  }
  record SequenceAccess(String astNodeId, SequenceBinding binding) {}
  record IndexOperand(String astNodeId, String declarationId, String displayName, int offset) {}
  record NormalizedComparison(String astNodeId, Expression left, BinaryExpr.Operator operator, Expression right, List<String> declarationIds) {
    NormalizedComparison { declarationIds = List.copyOf(declarationIds); }
  }
  record NormalizedComparatorComparison(String astNodeId, String callAstNodeId, ComparatorBinding comparator,
      Expression left, BinaryExpr.Operator operator, Expression right, List<String> declarationIds) {
    NormalizedComparatorComparison { declarationIds = List.copyOf(declarationIds); }
  }
  record Counter(String astNodeId, String declarationId, String displayName, int step, Expression initializer, String updateAstNodeId) {}
  record LoopBound(String astNodeId, IndexOperand index, SequenceBinding sequence, int lengthOffset, Optional<IndexOperand> pass) {}

  static Expression unwrap(Expression expression) { return SortAstSupport.unwrap(expression); }
  private static void recordEscapes(Expression expression, Map<String, SequenceBinding> known, Set<String> escapedDeclarations) {
    if (expression == null) return;
    for (NameExpr name : expression.findAll(NameExpr.class)) {
      if (scalarProjection(name, expression)) continue;
      SequenceBinding binding = LocalVariableBindings.resolve(name).map(known::get).orElse(null);
      if (binding != null) escapedDeclarations.add(binding.sourceDeclarationId());
      else recordEnclosingBindings(name, known, escapedDeclarations);
    }
  }
  private static void recordEnclosingBindings(NameExpr name, Map<String, SequenceBinding> known, Set<String> escapedDeclarations) {
    // LocalVariableBindings deliberately stops at lambdas and nested types. At an
    // escape sink, conservatively retain every matching enclosing receiver.
    for (Node current = name.getParentNode().orElse(null); current != null; current = current.getParentNode().orElse(null)) {
      if (!(current instanceof CallableDeclaration<?> callable)) continue;
      for (Parameter parameter : callable.getParameters()) {
        if (!parameter.getNameAsString().equals(name.getNameAsString())) continue;
        SequenceBinding binding = known.get(AstAnalyzer.nodeId(parameter));
        if (binding != null) escapedDeclarations.add(binding.sourceDeclarationId());
      }
      for (VariableDeclarator variable : callable.findAll(VariableDeclarator.class)) {
        if (!variable.getNameAsString().equals(name.getNameAsString())
            || LocalVariableBindings.callable(variable) != callable
            || variable.getBegin().isEmpty() || name.getBegin().isEmpty()
            || variable.getBegin().get().isAfter(name.getBegin().get())) continue;
        SequenceBinding binding = known.get(AstAnalyzer.nodeId(variable));
        if (binding != null) escapedDeclarations.add(binding.sourceDeclarationId());
      }
    }
  }
  private static boolean scalarProjection(NameExpr name, Expression expression) {
    for (Node parent = name.getParentNode().orElse(null); parent != null; parent = parent.getParentNode().orElse(null)) {
      if (parent instanceof ArrayAccessExpr access && within(name, access.getName())) return true;
      if (parent instanceof FieldAccessExpr field && field.getNameAsString().equals("length")
          && within(name, field.getScope())) return true;
      if (parent == expression) break;
    }
    return false;
  }
  private static boolean within(Node child, Node ancestor) {
    for (Node current = child; current != null; current = current.getParentNode().orElse(null))
      if (current == ancestor) return true;
    return false;
  }
  private static boolean one(Expression expression) { return unwrap(expression) instanceof IntegerLiteralExpr literal && literal.getValue().equals("1"); }
  private static boolean isZero(Expression expression) {
    return unwrap(expression) instanceof IntegerLiteralExpr literal && literal.getValue().equals("0");
  }
  private static Optional<String> comparatorElementType(String type) {
    String prefix = "java.util.Comparator<";
    if (!type.startsWith(prefix) || !type.endsWith(">")) return Optional.empty();
    String element = type.substring(prefix.length(), type.length() - 1);
    if (element.isEmpty() || element.contains("<") || element.contains(">") || element.contains(",")
        || element.contains("?") || element.contains("[")) return Optional.empty();
    return Optional.of(element);
  }
  private static boolean allowed(Node node, Node scope, Set<String> ids) {
    for (Node current = node; current != null; current = current.getParentNode().orElse(null)) {
      if (ids.contains(AstAnalyzer.nodeId(current))) return true;
      if (current == scope) break;
    }
    return false;
  }
  private static boolean isArray(String type) { return type.endsWith("[]"); }
  private static String elementType(String arrayType) { return arrayType.substring(0, arrayType.length() - 2); }
  private static boolean isNumericPrimitive(String type) {
    return switch (type == null ? "" : type) {
      case "byte", "short", "int", "long", "char", "float", "double" -> true;
      default -> false;
    };
  }
  private static SequenceKind kind(String type) {
    if (elementType(type).endsWith("[]")) return SequenceKind.OBJECT_ARRAY;
    if ("int[]".equals(type)) return SequenceKind.INT_ARRAY;
    return switch (elementType(type)) {
      case "byte", "char", "short", "long", "float", "double" -> SequenceKind.NUMERIC_PRIMITIVE_ARRAY;
      case "boolean" -> SequenceKind.BOOLEAN_ARRAY;
      default -> SequenceKind.OBJECT_ARRAY;
    };
  }
}
