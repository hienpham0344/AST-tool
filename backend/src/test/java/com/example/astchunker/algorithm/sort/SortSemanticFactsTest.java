package com.example.astchunker.algorithm.sort;

import static org.assertj.core.api.Assertions.assertThat;
import com.example.astchunker.ast.AstAnalyzer;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SortSemanticFactsTest {
  private final AstAnalyzer parser = new AstAnalyzer();

  @Test void preservesSourceAndCanonicalIdentityAndSiblingScopes() {
    var unit = parser.parseCompilationUnit("class S { void f(int[] a) { int[] alias=(a); int x=alias[0]; {int[] b=new int[2]; x=b[0];} {int[] b=new int[3]; x=b[0];} } }");
    var facts = SortSemanticFacts.analyze(unit);
    var accesses = unit.findAll(ArrayAccessExpr.class);
    var alias = facts.sequenceAccess(accesses.get(0));
    assertThat(alias).isPresent();
    assertThat(alias.get().binding().sourceDeclarationId()).isNotEqualTo(alias.get().binding().canonicalDeclarationId());
    assertThat(alias.get().binding().kind()).isEqualTo(SortSemanticFacts.SequenceKind.INT_ARRAY);
    assertThat(facts.sequenceAccess(accesses.get(1)).orElseThrow().binding().canonicalDeclarationId())
        .isNotEqualTo(facts.sequenceAccess(accesses.get(2)).orElseThrow().binding().canonicalDeclarationId());
  }

  @Test void normalizesStrictComparisonWithoutChangingSource() {
    var unit = parser.parseCompilationUnit("class S {void f(int[] a,int j){if(((a[j+1])<(a[j]))){} if(a[j]<=a[j+1]){} if(call()<a[j]){} if((j=1)>j){}}}");
    String before = unit.toString();
    var facts = SortSemanticFacts.analyze(unit);
    var conditions = unit.findAll(IfStmt.class);
    var comparison = facts.strictComparison(conditions.get(0).getCondition());
    assertThat(comparison).isPresent();
    assertThat(comparison.get().operator()).isEqualTo(BinaryExpr.Operator.GREATER);
    assertThat(comparison.get().left().toString()).isEqualTo("a[j]");
    assertThat(facts.indexOperand(comparison.get().right().asArrayAccessExpr().getIndex()).orElseThrow().offset()).isEqualTo(1);
    for (int i=1;i<conditions.size();i++) assertThat(facts.strictComparison(conditions.get(i).getCondition())).isEmpty();
    assertThat(unit.toString()).isEqualTo(before);
  }

  @Test void acceptsOnlyStructuralBoundsAndIntUnitCounters() {
    var unit = parser.parseCompilationUnit("class S {void f(int[] a,int pass){for(int j=0;a.length-1-pass>j;j=j+1){} for(int k=0;k<=a.length-2;k++){} for(int m=0;m<a.length-1;m+=1){} for(int n=4;n>0;n-=1){} for(long q=0;q<a.length;q++){} }}");
    var facts = SortSemanticFacts.analyze(unit);
    var loops = unit.findAll(ForStmt.class);
    var sequence = facts.sequenceAccess(unit.findFirst(NameExpr.class,n->n.getNameAsString().equals("a")).orElseThrow()).orElseThrow().binding();
    assertThat(facts.forwardUnitCounter(loops.get(0))).isPresent();
    var bound = facts.supportedLengthBound(loops.get(0),sequence);
    assertThat(bound).isPresent();
    assertThat(bound.get().pass()).isPresent();
    assertThat(bound.get().lengthOffset()).isEqualTo(-1);
    assertThat(facts.supportedLengthBound(loops.get(1),sequence)).isEmpty();
    assertThat(facts.forwardUnitCounter(loops.get(2))).isPresent();
    assertThat(facts.backwardUnitCounter(loops.get(3))).isPresent();
    assertThat(facts.forwardUnitCounter(loops.get(4))).isEmpty();
  }

  @Test void rejectsReassignedOrEscapedAliasAndUnknownArrayInitializers() {
    for (String body : new String[]{"int[] b=a; b=new int[2]; int x=b[0];", "int[] b=a; outside(b); int x=b[0];", "int[] b=outside(); int x=b[0];"}) {
      var unit = parser.parseCompilationUnit("class S {void f(int[] a){"+body+"}}");
      assertThat(SortSemanticFacts.analyze(unit).sequenceAccess(unit.findFirst(ArrayAccessExpr.class).orElseThrow())).isEmpty();
    }
  }

  @Test void reassignmentAndEscapeInvalidateOnlyAffectedAliasIdentity() {
    for (String effect : new String[]{"b=new int[1];", "outside(b);"}) {
      var unit = parser.parseCompilationUnit("class S {void f(int[] a){int[] b=a; int[] c=a; "
          + effect + " int x=a[0]+c[0]+b[0];}}");
      var facts = SortSemanticFacts.analyze(unit);
      var accesses = unit.findAll(ArrayAccessExpr.class);
      assertThat(facts.sequenceAccess(accesses.get(0))).as(effect).isPresent();
      assertThat(facts.sequenceAccess(accesses.get(1))).as(effect).isPresent();
      assertThat(facts.sequenceAccess(accesses.get(2))).as(effect).isEmpty();
    }
    var chained = parser.parseCompilationUnit("class S {void f(int[] a){int[] b=a; int[] c=b; outside(c); int x=b[0];}}");
    assertThat(SortSemanticFacts.analyze(chained).sequenceAccess(chained.findFirst(ArrayAccessExpr.class).orElseThrow())).isPresent();
  }

  @Test void aliasCreatedFromReassignedSourceCannotReuseOldCanonicalIdentity() {
    for (String body : new String[]{
        "int[] b=a; b=new int[1]; int[] c=b;",
        "a=new int[1]; int[] c=a;"}) {
      var unit = parser.parseCompilationUnit("class S {void f(int[] a){" + body + "int x=c[0]+a[0];}}");
      var facts = SortSemanticFacts.analyze(unit);
      assertThat(facts.sequenceAccess(unit.findAll(ArrayAccessExpr.class).get(0))).as(body).isEmpty();
    }
  }

  @Test void rejectsAliasWhenCanonicalReceiverEscapesThroughCallReturnOrConstructor() {
    for (String body : new String[]{
        "outside(a); int x=b[0];",
        "new Keeper(a); int x=b[0];",
        "int x=b[0]; return a;"}) {
      String method = body.contains("return a") ? "int[] f(int[] a)" : "void f(int[] a)";
      var unit = parser.parseCompilationUnit("class S {" + method + " { int[] b=a; " + body + " }}");
      assertThat(SortSemanticFacts.analyze(unit).sequenceAccess(unit.findFirst(ArrayAccessExpr.class).orElseThrow()))
          .as(body).isEmpty();
    }
  }

  @Test void rejectsAliasWhenIdentityPreservingCastWrapsAnEscape() {
    for (String body : new String[]{
        "outside((int[]) a); int x=b[0];",
        "new Keeper(((int[]) (a))); int x=b[0];",
        "int x=b[0]; return (int[]) a;",
        "field=(int[]) a; int x=b[0];",
        "this.field=(int[]) a; int x=b[0];"}) {
      String method = body.contains("return") ? "int[] f(int[] a)" : "void f(int[] a)";
      var unit = parser.parseCompilationUnit("class S { int[] field; " + method
          + " { int[] b=a; " + body + " }}");
      assertThat(SortSemanticFacts.analyze(unit).sequenceAccess(unit.findFirst(ArrayAccessExpr.class).orElseThrow()))
          .as(body).isEmpty();
    }
  }

  @Test void rejectsAliasWhenNestedExpressionMayExposeCanonicalReceiver() {
    for (String body : new String[]{
        "outside(flag ? a : a); int x=b[0];",
        "outside((Object) (flag ? a : new int[0])); int x=b[0];",
        "new Keeper(flag ? a : a); int x=b[0];",
        "int x=b[0]; return flag ? a : a;",
        "field=flag ? a : a; int x=b[0];",
        "outside(new Object[]{a}); int x=b[0];"}) {
      String method = body.contains("return") ? "int[] f(int[] a,boolean flag)" : "void f(int[] a,boolean flag)";
      var unit = parser.parseCompilationUnit("class S { int[] field; " + method
          + " { int[] b=a; " + body + " }}");
      assertThat(SortSemanticFacts.analyze(unit).sequenceAccess(unit.findFirst(ArrayAccessExpr.class).orElseThrow()))
          .as(body).isEmpty();
    }
  }

  @Test void scalarProjectionsDoNotEscapeArrayIdentity() {
    var unit = parser.parseCompilationUnit("class S { void f(int[] a) { int[] b=a; log(a.length); log(a[0]); int x=b[0]; } }");
    var access = unit.findAll(ArrayAccessExpr.class).get(1);
    assertThat(SortSemanticFacts.analyze(unit).sequenceAccess(access)).isPresent();
  }

  @Test void unsafeSameFileHelperCannotHideCanonicalReceiverEscape() {
    for (String helpers : new String[]{
        "private static void publish(int[] x){ leaked=x; }",
        "private static void publish(int[] x){ leaked=x; }"
            + "private static void wrap(int[] x){ publish(x); }"}) {
      String call = helpers.contains("wrap") ? "wrap(a)" : "publish(a)";
      var unit = parser.parseCompilationUnit("class S { static int[] leaked;"
          + " void f(int[] a){ int[] b=a; " + call + "; int x=b[0]; }" + helpers + " }");
      assertThat(SortSemanticFacts.analyze(unit).sequenceAccess(unit.findFirst(ArrayAccessExpr.class).orElseThrow()))
          .as(call).isEmpty();
    }
  }

  @Test void lambdaCaptureCanPublishCanonicalReceiver() {
    for (String body : new String[]{
        "outside(() -> a); int x=b[0];",
        "outside(new java.util.function.Supplier<int[]>() { public int[] get(){ return a; } }); int x=b[0];",
        "java.util.function.Supplier<int[]> captured=() -> a; outside(captured); int x=b[0];",
        "new Keeper(() -> a); int x=b[0];",
        "sink=() -> a; int x=b[0];",
        "int x=b[0]; return () -> a;"}) {
      String method = body.contains("return") ? "java.util.function.Supplier<int[]> f(int[] a)" : "void f(int[] a)";
      var unit = parser.parseCompilationUnit("class S { java.util.function.Supplier<int[]> sink; "
          + method + " { int[] b=a; " + body + " }}");
      assertThat(SortSemanticFacts.analyze(unit).sequenceAccess(unit.findFirst(ArrayAccessExpr.class).orElseThrow()))
          .as(body).isEmpty();
    }
  }

  @Test void lambdaScalarProjectionDoesNotPublishArrayIdentity() {
    var unit = parser.parseCompilationUnit("class S { void f(int[] a) { int[] b=a; outside(() -> a.length); int x=b[0]; } }");
    assertThat(SortSemanticFacts.analyze(unit).sequenceAccess(unit.findFirst(ArrayAccessExpr.class).orElseThrow()))
        .isPresent();
  }

  @Test void classifiesMutationWithExplicitAllowedSourceSites() {
    var unit = parser.parseCompilationUnit("class S {void f(int[] a,int j){a[j]=1; j++; outside(a);}}");
    var facts = SortSemanticFacts.analyze(unit);
    var access = unit.findFirst(ArrayAccessExpr.class).orElseThrow();
    var sequence = facts.sequenceAccess(access).orElseThrow().binding();
    var body = unit.findFirst(BlockStmt.class).orElseThrow();
    assertThat(facts.hasInterferingMutation(body,sequence,Set.of())).isTrue();
    var write = body.getStatement(0);
    assertThat(facts.hasInterferingMutation(write,sequence,Set.of(AstAnalyzer.nodeId(write)))).isFalse();
    assertThat(facts.hasInterferingMutation(body.getStatement(1),sequence,Set.of())).isTrue();
    assertThat(facts.hasInterferingMutation(body.getStatement(2),sequence,Set.of())).isTrue();
  }

  @Test void retainsExactNumericPrimitiveArrayAndElementTypesForAliases() {
    var unit = parser.parseCompilationUnit("class S { void f(long[] a) { long[] b=a; long x=b[0]; } }");
    var facts = SortSemanticFacts.analyze(unit);
    var binding = facts.sequenceAccess(unit.findFirst(ArrayAccessExpr.class).orElseThrow()).orElseThrow().binding();

    assertThat(binding.kind()).isEqualTo(SortSemanticFacts.SequenceKind.NUMERIC_PRIMITIVE_ARRAY);
    assertThat(binding.arrayType()).isEqualTo("long[]");
    assertThat(binding.elementType()).isEqualTo("long");
  }

  @Test void rejectsCastsAndKeepsBooleanObjectAndNestedArraysOutOfNumericComparisons() {
    var cast = parser.parseCompilationUnit("class S { void f(long[] a) { int[] b=(int[]) (Object) a; int x=b[0]; } }");
    assertThat(SortSemanticFacts.analyze(cast).sequenceAccess(cast.findFirst(ArrayAccessExpr.class).orElseThrow())).isEmpty();

    for (String source : new String[]{
        "class S { void f(boolean[] a,int j) { if(a[j] < a[j+1]){} } }",
        "class S { void f(String[] a,int j) { if(a[j] < a[j+1]){} } }",
        "class S { void f(int[][] a,int j) { if(a[j][0] < a[j+1][0]){} } }"}) {
      var unit = parser.parseCompilationUnit(source);
      var facts = SortSemanticFacts.analyze(unit);
      assertThat(facts.strictComparison(unit.findFirst(IfStmt.class).orElseThrow().getCondition())).as(source).isEmpty();
    }
    for (String type : new String[]{"long[][]", "double[][]"}) {
      var unit = parser.parseCompilationUnit("class S { void f(" + type + " a) { " + type + " b = a; } }");
      var binding = SortSemanticFacts.analyze(unit)
          .sequenceAccess(unit.findFirst(com.github.javaparser.ast.expr.NameExpr.class,
              name -> name.getNameAsString().equals("a")).orElseThrow())
          .orElseThrow().binding();
      assertThat(binding.kind()).as(type).isEqualTo(SortSemanticFacts.SequenceKind.OBJECT_ARRAY);
      assertThat(binding.isNumericPrimitiveArray()).as(type).isFalse();
    }
  }

  @Test void normalizesNumericPrimitiveArrayComparisonWhileKeepingIndexArithmeticIntOnly() {
    var unit = parser.parseCompilationUnit("class S { void f(long[] a, int j, long k) { if(a[j] < k){} if(a[j] < (int) k){} if(a[(long) j] < a[j+1]){} } }");
    var facts = SortSemanticFacts.analyze(unit);
    var conditions = unit.findAll(IfStmt.class);

    assertThat(facts.strictComparison(conditions.get(0).getCondition())).isPresent();
    assertThat(facts.strictComparison(conditions.get(1).getCondition())).isEmpty();
    assertThat(facts.strictComparison(conditions.get(2).getCondition())).isEmpty();
  }

  @Test void normalizesDirectFullyQualifiedComparatorAgainstZeroForReferenceArrayAlias() {
    var unit = parser.parseCompilationUnit("""
        class S {
          void sort(String[] values, java.util.Comparator<String> order, int scan) {
            String[] alias = values;
            if (order.compare(alias[scan], values[scan + 1]) > 0) {}
            if (0 > order.compare(values[scan], alias[scan + 1])) {}
          }
        }
        """);
    var facts = SortSemanticFacts.analyze(unit);
    var conditions = unit.findAll(IfStmt.class);

    var forward = facts.strictComparatorComparison(conditions.get(0).getCondition());
    assertThat(forward).isPresent();
    assertThat(forward.orElseThrow().operator()).isEqualTo(BinaryExpr.Operator.GREATER);
    assertThat(forward.orElseThrow().left().toString()).isEqualTo("alias[scan]");
    assertThat(forward.orElseThrow().right().toString()).isEqualTo("values[scan + 1]");
    assertThat(forward.orElseThrow().comparator().elementType()).isEqualTo("String");
    assertThat(forward.orElseThrow().comparator().supports(
        facts.sequenceAccess(unit.findAll(ArrayAccessExpr.class).get(0)).orElseThrow().binding())).isTrue();

    var reversed = facts.strictComparatorComparison(conditions.get(1).getCondition());
    assertThat(reversed).isPresent();
    assertThat(reversed.orElseThrow().operator()).isEqualTo(BinaryExpr.Operator.LESS);
    assertThat(reversed.orElseThrow().left().toString()).isEqualTo("values[scan]");
    assertThat(reversed.orElseThrow().right().toString()).isEqualTo("alias[scan + 1]");
  }

  @Test void rejectsComparatorNearMissesOutsideTheDirectExactBoundary() {
    for (String parameter : new String[]{
        "java.util.Comparator order",
        "java.util.Comparator<?> order",
        "Comparator<String> order"}) {
      String source = "class S { void sort(String[] values, " + parameter
          + ", int scan) { if (order.compare(values[scan], values[scan + 1]) > 0) {} } }";
      var unit = parser.parseCompilationUnit(source);
      assertThat(SortSemanticFacts.analyze(unit)
          .strictComparatorComparison(unit.findFirst(IfStmt.class).orElseThrow().getCondition()))
          .as(parameter).isEmpty();
    }

    var mismatched = parser.parseCompilationUnit("class S { void sort(String[] values, java.util.Comparator<Object> order, int scan) { if (order.compare(values[scan], values[scan + 1]) > 0) {} } }");
    var mismatch = SortSemanticFacts.analyze(mismatched);
    var comparison = mismatch.strictComparatorComparison(mismatched.findFirst(IfStmt.class).orElseThrow().getCondition());
    assertThat(comparison).isPresent();
    assertThat(comparison.orElseThrow().comparator().supports(
        mismatch.sequenceAccess(mismatched.findFirst(ArrayAccessExpr.class).orElseThrow()).orElseThrow().binding())).isFalse();

    for (String body : new String[]{
        "if (order.compare(values[scan], values[scan + 1]) > 1) {}",
        "if (this.order.compare(values[scan], values[scan + 1]) > 0) {}",
        "java.util.Comparator<String> alias = order; if (alias.compare(values[scan], values[scan + 1]) > 0) {}",
        "if (order.compare(values[scan], values[scan + 1]).intValue() > 0) {}"}) {
      var unit = parser.parseCompilationUnit("class S { void sort(String[] values, java.util.Comparator<String> order, java.util.Comparator<String> other, int scan) { " + body + " } }");
      assertThat(SortSemanticFacts.analyze(unit)
          .strictComparatorComparison(unit.findFirst(IfStmt.class).orElseThrow().getCondition()))
          .as(body).isEmpty();
    }
  }

  @Test void normalizesDirectNaturalOrderComparisonForEveryExactJdkElementType() {
    for (String type : new String[]{
        "java.lang.String", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
        "java.lang.Long", "java.lang.Character", "java.lang.Float", "java.lang.Double",
        "java.lang.Boolean"}) {
      var unit = parser.parseCompilationUnit("class S { void sort(" + type
          + "[] values, int scan) { if (values[scan].compareTo(values[scan + 1]) > 0) {}"
          + " if (0 > values[scan + 1].compareTo(values[scan])) {} } }");
      var facts = SortSemanticFacts.analyze(unit);
      var conditions = unit.findAll(IfStmt.class);

      var forward = facts.strictNaturalOrderComparison(conditions.get(0).getCondition());
      assertThat(forward).as(type).isPresent();
      assertThat(forward.orElseThrow().operator()).as(type).isEqualTo(BinaryExpr.Operator.GREATER);
      assertThat(forward.orElseThrow().receiver().toString()).as(type).isEqualTo("values[scan]");
      assertThat(forward.orElseThrow().argument().toString()).as(type).isEqualTo("values[scan + 1]");
      assertThat(forward.orElseThrow().naturalOrder().elementType()).as(type).isEqualTo(type);
      assertThat(forward.orElseThrow().naturalOrder().supports(
          facts.sequenceAccess(unit.findAll(ArrayAccessExpr.class).get(0)).orElseThrow().binding())).as(type).isTrue();
      assertThat(forward.orElseThrow().callAstNodeId()).as(type)
          .isEqualTo(AstAnalyzer.nodeId(unit.findFirst(MethodCallExpr.class).orElseThrow()));
      assertThat(forward.orElseThrow().astNodeId()).as(type)
          .isEqualTo(AstAnalyzer.nodeId(conditions.get(0).getCondition()));

      var reversed = facts.strictNaturalOrderComparison(conditions.get(1).getCondition());
      assertThat(reversed).as(type).isPresent();
      assertThat(reversed.orElseThrow().operator()).as(type).isEqualTo(BinaryExpr.Operator.LESS);
      assertThat(reversed.orElseThrow().receiver().toString()).as(type).isEqualTo("values[scan + 1]");
      assertThat(reversed.orElseThrow().argument().toString()).as(type).isEqualTo("values[scan]");
    }
  }

  @Test void recognizesShortJdkStringOnlyAfterResolvingTheCallAndBothOperands() {
    for (String prefix : List.of("", "import java.lang.String; ")) {
      var unit = parser.parseCompilationUnit(prefix + "class S { void sort(String[] values, int scan) {"
          + " if (values[scan].compareTo(values[scan + 1]) > 0) {} } }");
      var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
      String originalCallId = AstAnalyzer.nodeId(call);
      var facts = SortSemanticFacts.analyze(unit);

      var comparison = facts.strictNaturalOrderComparison(
          unit.findFirst(IfStmt.class).orElseThrow().getCondition());
      assertThat(comparison).as(prefix).isPresent();
      assertThat(comparison.orElseThrow().naturalOrder().elementType()).isEqualTo("java.lang.String");
      assertThat(comparison.orElseThrow().naturalOrder().supports(
          facts.sequenceAccess(unit.findFirst(ArrayAccessExpr.class).orElseThrow()).orElseThrow().binding()))
          .isTrue();
      assertThat(comparison.orElseThrow().callAstNodeId()).isEqualTo(originalCallId);
    }
  }

  @Test void recognizesShortJdkWrapperTypesOnlyWhenTheResolvedCallMatches() {
    for (String simple : List.of("Byte", "Short", "Integer", "Long", "Character", "Float", "Double", "Boolean")) {
      var unit = parser.parseCompilationUnit("class S { void sort(" + simple + "[] values, int scan) {"
          + " if (values[scan].compareTo(values[scan + 1]) > 0) {} } }");
      var facts = SortSemanticFacts.analyze(unit);
      var comparison = facts.strictNaturalOrderComparison(
          unit.findFirst(IfStmt.class).orElseThrow().getCondition());

      assertThat(comparison).as(simple).isPresent();
      assertThat(comparison.orElseThrow().naturalOrder().elementType())
          .as(simple).isEqualTo("java.lang." + simple);
      assertThat(comparison.orElseThrow().naturalOrder().supports(
          facts.sequenceAccess(unit.findFirst(ArrayAccessExpr.class).orElseThrow()).orElseThrow().binding()))
          .as(simple).isTrue();
    }
  }

  @Test void resolvesFullyQualifiedJdkCallsAcrossPackageAndImportDeclarations() {
    var unit = parser.parseCompilationUnit("package demo; import java.util.List;"
        + " class S { void sort(java.lang.String[] values, int scan) {"
        + " if (values[scan].compareTo(values[scan + 1]) > 0) {} } }");
    var facts = SortSemanticFacts.analyze(unit);

    var comparison = facts.strictNaturalOrderComparison(
        unit.findFirst(IfStmt.class).orElseThrow().getCondition());
    assertThat(comparison).isPresent();
    assertThat(comparison.orElseThrow().naturalOrder().elementType())
        .isEqualTo("java.lang.String");
  }

  @Test void rejectsShortJdkWrapperNameWhenShadowedBySourceType() {
    var unit = parser.parseCompilationUnit("""
        class S {
          static final class Integer implements Comparable<Integer> {
            public int compareTo(Integer other) { return 0; }
          }
          void sort(Integer[] values, int i) {
            if (values[i].compareTo(values[i + 1]) > 0) {}
          }
        }
        """);

    assertThat(SortSemanticFacts.analyze(unit).strictNaturalOrderComparison(
        unit.findFirst(IfStmt.class).orElseThrow().getCondition())).isEmpty();
  }

  @Test void preservesAnExistingCallerSymbolResolver() {
    var unit = parser.parseCompilationUnit("class S { void sort(String[] values, int i) {"
        + " if (values[i].compareTo(values[i + 1]) > 0) {} } }");
    var callerResolver = new JavaSymbolSolver(new ReflectionTypeSolver(true));
    callerResolver.inject(unit);

    SortSemanticFacts.analyze(unit);

    assertThat(unit.getData(Node.SYMBOL_RESOLVER_KEY)).isSameAs(callerResolver);
    assertThat(unit.findFirst(MethodCallExpr.class).orElseThrow().resolve()
        .declaringType().getQualifiedName()).isEqualTo("java.lang.String");
  }

  @Test void rejectsShortNameShadowInProductionFacts() {
    var unit = parser.parseCompilationUnit("""
        class S {
          static final class String implements Comparable<String> {
            public int compareTo(String other) { return 0; }
          }
          void sort(String[] values, int i) {
            if (values[i].compareTo(values[i + 1]) > 0) {}
          }
        }
        """);

    assertThat(SortSemanticFacts.analyze(unit).strictNaturalOrderComparison(
        unit.findFirst(IfStmt.class).orElseThrow().getCondition())).isEmpty();
    var inherited = parser.parseCompilationUnit("""
        class Base {
          static final class String implements Comparable<String> {
            public int compareTo(String other) { return 0; }
          }
        }
        class S extends Base {
          void sort(String[] values, int i) {
            if (values[i].compareTo(values[i + 1]) > 0) {}
          }
        }
        """);
    assertThat(SortSemanticFacts.analyze(inherited).strictNaturalOrderComparison(
        inherited.findFirst(IfStmt.class).orElseThrow().getCondition())).isEmpty();
    var typeParameter = parser.parseCompilationUnit("""
        class S<String extends Comparable<String>> {
          void sort(String[] values, int i) {
            if (values[i].compareTo(values[i + 1]) > 0) {}
          }
        }
        """);
    assertThat(SortSemanticFacts.analyze(typeParameter).strictNaturalOrderComparison(
        typeParameter.findFirst(IfStmt.class).orElseThrow().getCondition())).isEmpty();
  }

  @Test void acceptsSavedNaturalOrderKeyButRejectsEveryUnsafeNaturalOrderNearMiss() {
    var savedKey = parser.parseCompilationUnit("class S { void sort(java.lang.String[] values, int scan) {"
        + " java.lang.String key=values[scan]; if (key.compareTo(values[scan - 1]) < 0) {} } }");
    var savedKeyComparison = SortSemanticFacts.analyze(savedKey).strictNaturalOrderComparison(
        savedKey.findFirst(IfStmt.class).orElseThrow().getCondition());
    assertThat(savedKeyComparison).isPresent();
    assertThat(savedKeyComparison.orElseThrow().receiver()).isInstanceOf(NameExpr.class);
    assertThat(savedKeyComparison.orElseThrow().argument()).isInstanceOf(ArrayAccessExpr.class);

    for (String source : new String[]{
        "class S { void sort(java.lang.String[] values, int scan) { if (((java.lang.String) values[scan]).compareTo(values[scan + 1]) > 0) {} } }",
        "class S { void sort(java.lang.String[] values, int scan) { if (values[scan].trim().compareTo(values[scan + 1]) > 0) {} } }",
        "class S { void sort(java.lang.String[] values, int scan) { if (values[scan].compareTo(values[scan + 1], values[scan]) > 0) {} } }",
        "class S { void sort(java.lang.String[] values, int scan) { if (values[scan].<String>compareTo(values[scan + 1]) > 0) {} } }",
        "class S { void sort(java.lang.String[] values, int scan) { if (values[scan].compareTo(values[scan + 1]) >= 0) {} } }",
        "class S { void sort(java.lang.String[] values, int scan) { if (values[scan].compareTo(values[scan + 1]) > 1) {} } }",
        "class S { void sort(java.lang.String[][] values, int scan) { if (values[scan].compareTo(values[scan + 1]) > 0) {} } }",
        "class S { void sort(java.lang.String[] values, int scan) { if (holder.compareTo(values[scan + 1]) > 0) {} } }"}) {
      var unit = parser.parseCompilationUnit(source);
      assertThat(SortSemanticFacts.analyze(unit).strictNaturalOrderComparison(
          unit.findFirst(IfStmt.class).orElseThrow().getCondition())).as(source).isEmpty();
    }
  }

  @Test void rejectsShadowedJavaLangTypeDespiteFullyQualifiedSourceSpelling() {
    String source = """
        class S {
          static class java {
            static class lang {
              static final class String implements Comparable<String> {
                public int compareTo(String other) { return 0; }
              }
            }
          }
          void sort(java.lang.String[] values, int scan) {
            if (values[scan].compareTo(values[scan + 1]) > 0) {}
          }
        }
        """;
    var unit = parser.parseCompilationUnit(source);
    assertThat(SortSemanticFacts.analyze(unit).strictNaturalOrderComparison(
        unit.findFirst(IfStmt.class).orElseThrow().getCondition())).isEmpty();
    var importedShadow = parser.parseCompilationUnit("import java.util.List; " + source);
    assertThat(SortSemanticFacts.analyze(importedShadow).strictNaturalOrderComparison(
        importedShadow.findFirst(IfStmt.class).orElseThrow().getCondition())).isEmpty();
    var inheritedShadow = parser.parseCompilationUnit("""
        class Base {
          static class java {
            static class lang {
              static final class String implements Comparable<String> {
                public int compareTo(String other) { return 0; }
              }
            }
          }
        }
        class S extends Base {
          void sort(java.lang.String[] values, int scan) {
            if (values[scan].compareTo(values[scan + 1]) > 0) {}
          }
        }
        """);
    assertThat(SortSemanticFacts.analyze(inheritedShadow).strictNaturalOrderComparison(
        inheritedShadow.findFirst(IfStmt.class).orElseThrow().getCondition())).isEmpty();

    String simple = "class S { void sort(java.lang.String[] values, int scan) {"
        + " if (values[scan].compareTo(values[scan + 1]) > 0) {} } }";
    for (String sourceWithResolvableJdkType : List.of(
        "import java.util.*; " + simple,
        simple.replace("class S {", "class S extends java.util.ArrayList<Object> {"),
        simple.replace("void sort", "void unrelated() { new Object() {}; } void sort"))) {
      var resolved = parser.parseCompilationUnit(sourceWithResolvableJdkType);
      assertThat(SortSemanticFacts.analyze(resolved).strictNaturalOrderComparison(
          resolved.findFirst(IfStmt.class).orElseThrow().getCondition()))
          .as(sourceWithResolvableJdkType).isPresent();
    }
  }
}
