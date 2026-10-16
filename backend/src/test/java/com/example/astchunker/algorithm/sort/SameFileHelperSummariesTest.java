package com.example.astchunker.algorithm.sort;

import static org.assertj.core.api.Assertions.assertThat;
import com.example.astchunker.ast.AstAnalyzer;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SameFileHelperSummariesTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private static final String SWAP = "private static void exchange(int[] v,int x,int y){int t=v[x];v[x]=v[y];v[y]=t;}";

  @Test void substitutesExchangeArgumentsAndPreservesAllProvenance() {
    var unit = parser.parseCompilationUnit("class S {void run(int[] a,int j){int[] b=a; exchange(b,j,j+1);}"+SWAP+"}");
    String before = unit.toString();
    var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
    var helpers = SameFileHelperSummaries.analyze(unit, SortSemanticFacts.analyze(unit));
    var result = helpers.resolveExchange(call);
    assertThat(result.status()).isEqualTo(SameFileHelperSummaries.Status.RESOLVED);
    assertThat(result.value()).isPresent();
    var exchange = result.value().get();
    assertThat(exchange.callSiteId()).isEqualTo(AstAnalyzer.nodeId(call));
    var helper = unit.findAll(MethodDeclaration.class).get(1);
    assertThat(exchange.helperCallableId()).isEqualTo(AstAnalyzer.nodeId(helper));
    assertThat(exchange.statementAstNodeIds()).containsExactlyElementsOf(helper.getBody().orElseThrow().getStatements().stream().map(AstAnalyzer::nodeId).toList());
    assertThat(exchange.firstIndex().declarationId()).isEqualTo(exchange.secondIndex().declarationId());
    assertThat(exchange.secondIndex().offset()).isEqualTo(1);
    assertThat(exchange.sequence().sourceDeclarationId()).isNotEqualTo(exchange.sequence().canonicalDeclarationId());
    assertThat(unit.toString()).isEqualTo(before);
  }

  @Test void summarizesStrictArrayAndScalarComparisonHelpers() {
    for (String helpers : List.of("private boolean less(int[] a,int x,int y){return a[x]<a[y];}","private boolean less(int[] a,int x,int y){return cmp(a[x],a[y]);} private static boolean cmp(int x,int y){return x<y;}",
        "private boolean less(int[] a,int x,int y){return cmp(a[x],a[y]);} private static boolean cmp(int x,int y){return x<y;} private static boolean cmp(long x,long y){return false;}")) {
      var unit = parser.parseCompilationUnit("class S {void run(int[] a,int j){if(less(a,j,j+1)){} }"+helpers+"}");
      var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
      var result = SameFileHelperSummaries.analyze(unit,SortSemanticFacts.analyze(unit)).resolveComparison(call);
      assertThat(result.status()).isEqualTo(SameFileHelperSummaries.Status.RESOLVED);
      assertThat(result.value()).isPresent();
      var comparison = result.value().get();
      assertThat(comparison.operator()).isEqualTo(BinaryExpr.Operator.GREATER);
      assertThat(comparison.left().sequence()).isPresent();
      assertThat(comparison.left().index().orElseThrow().offset()).isEqualTo(1);
      assertThat(comparison.right().index().orElseThrow().offset()).isZero();
      assertThat(comparison.callSiteId()).isEqualTo(AstAnalyzer.nodeId(call));
      assertThat(comparison.statementAstNodeIds()).isNotEmpty();
    }
  }

  @Test void supportsOneForwardingWrapperButRejectsDepthAndCycles() {
    assertExchange("wrap(a,j,j+1)","private static void wrap(int[] b,int x,int y){exchange(b,x,y);}"+SWAP,SameFileHelperSummaries.Status.RESOLVED);
    assertExchange("wrap(a,j,j+1)","private static void wrap(int[] b,int x,int y){next(b,x,y);} private static void next(int[] b,int x,int y){exchange(b,x,y);}"+SWAP,SameFileHelperSummaries.Status.BUDGET_EXCEEDED);
    assertExchange("wrap(a,j,j+1)","private static void wrap(int[] b,int x,int y){wrap(b,x,y);}",SameFileHelperSummaries.Status.CYCLE);
    assertExchange("wrap(a,j,j+1)","private static void wrap(int[] b,int x,int y){next(b,x,y);} private static void next(int[] b,int x,int y){wrap(b,x,y);}",SameFileHelperSummaries.Status.CYCLE);
  }

  @Test void rejectsFakeExchangeAdditionalEffectsAndWrongBinding() {
    assertExchange("exchange(a,j,j+1)","private static void exchange(int[] v,int x,int y){int t=v[x];v[x]=v[y];v[y]=v[x];}",SameFileHelperSummaries.Status.UNRESOLVED);
    assertExchange("exchange(a,j,j+1)",SWAP.replace("v[y]=t;","v[y]=t; x++;"),SameFileHelperSummaries.Status.INTERFERING_EFFECT);
    assertExchange("exchange(a,j,j+1)",SWAP.replace("v[y]=t;","v[y]=t; v[0]=1;"),SameFileHelperSummaries.Status.INTERFERING_EFFECT);
    assertExchange("exchange(a,j,j+1)",SWAP.replace("v[y]=t;","v[y]=x;"),SameFileHelperSummaries.Status.UNRESOLVED);
  }

  @Test void rejectsExternalVirtualAmbiguousAndOverBudgetHelpers() {
    assertExchange("Other.exchange(a,j,j+1)",SWAP,SameFileHelperSummaries.Status.EXTERNAL_OR_DYNAMIC);
    assertExchange("exchange(a,j,j+1)",SWAP.replace("private static", "public"),SameFileHelperSummaries.Status.EXTERNAL_OR_DYNAMIC);
    assertExchange("exchange(a,j,j+1)",SWAP+"private static void exchange(int[] v,long x,int y){}",SameFileHelperSummaries.Status.RESOLVED);
    assertExchange("exchange(a,j,j+1)",SWAP.replace("int t=v[x];", extraStatements(33)+"int t=v[x];"),SameFileHelperSummaries.Status.BUDGET_EXCEEDED);
  }

  @Test void fieldWithOwnerNameCannotImpersonateAStaticTypeQualifier() {
    var unit = parser.parseCompilationUnit("class S { Other S; void run(int[] a,int j){ S.exchange(a,j,j+1); }"
        + SWAP + "} class Other { void exchange(int[] a,int i,int j){} }");
    var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
    var result = SameFileHelperSummaries.analyze(unit, SortSemanticFacts.analyze(unit)).resolveExchange(call);
    assertThat(result.status()).isEqualTo(SameFileHelperSummaries.Status.EXTERNAL_OR_DYNAMIC);
    assertThat(result.value()).isEmpty();
  }

  private void assertExchange(String callSource, String helpers, SameFileHelperSummaries.Status expected) {
    var unit = parser.parseCompilationUnit("class S {void run(int[] a,int j){"+callSource+";}"+helpers
        +"} class Other { static void exchange(int[] a,int i,int j){} }");
    var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
    var summaries = SameFileHelperSummaries.analyze(unit,SortSemanticFacts.analyze(unit));
    var result = summaries.resolveExchange(call);
    assertThat(result.status()).as(callSource+helpers).isEqualTo(expected);
    if (expected != SameFileHelperSummaries.Status.RESOLVED) {
      assertThat(result.value()).isEmpty();
      assertThat(result.limitations()).isNotEmpty();
      assertThat(summaries.resolveExchange(call)).isEqualTo(result);
    }
  }

  private static String extraStatements(int count) {
    StringBuilder source = new StringBuilder();
    for (int index = 0; index < count; index++) source.append("int z").append(index).append("=0;");
    return source.toString();
  }
}
