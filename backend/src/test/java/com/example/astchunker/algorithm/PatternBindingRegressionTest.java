package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatternBindingRegressionTest {
  private List<AlgorithmHint> detect(String body) {
    return new AlgorithmPatternAnalyzer()
        .analyze(
            new AstAnalyzer()
                .parseCompilationUnit("class Sample { void f(int[] a, int k) { " + body + " } }"));
  }

  @Test
  void doesNotCombineDifferentAccumulatorsWithTheSameName() {
    assertThat(
            detect(
                """
        for (int r = 0; r < a.length; r++) {
          { int sum = 0; sum += a[r]; }
          { int sum = 0; if (r >= k) sum -= a[r-k]; }
        }
        """))
        .isEmpty();
  }

  @Test
  void recognizesOneAccumulatorAcrossNestedBlocks() {
    assertThat(
            detect(
                """
        int sum = 0;
        for (int r = 0; r < a.length; r++) {
          { sum += a[r]; }
          { if (r >= k) sum -= a[r-k]; }
        }
        """))
        .extracting(AlgorithmHint::type)
        .containsExactly("sliding-window");
  }

  @Test
  void recordsEquivalentSyntaxStillOutsideTheCurrentRuleCoverage() {
    String source = "int l=0,r=a.length-1; while(l<r){int x=a[l]+a[r];l++;r--;}";
    assertThat(detect(source)).hasSize(1);
    // Expression normalization is task 4; do not claim that these are supported yet.
    assertThat(detect(source.replace("l<r", "r>l"))).isEmpty();
  }

  @Test
  void hintsCarryExactPatternMethodAndDeclarationIds() {
    var parser = new AstAnalyzer();
    var analysis =
        parser.analyze(
            "class S { void f(int[] a,int l,int r){while(l<r){int x=a[l]+a[r];l++;r--;}} }");
    var hint = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit()).get(0);
    assertThat(hint.patternAstNodeId()).startsWith("ast-WhileStmt-");
    assertThat(hint.methodAstNodeId()).startsWith("ast-MethodDeclaration-");
    assertThat(hint.variableDeclarationIds()).hasSize(3);
    hint.variableDeclarationIds()
        .forEach(
            (role, id) ->
                assertThat(analysis.variables())
                    .anySatisfy(
                        v -> {
                          assertThat(v.astNodeId()).isEqualTo(id);
                          assertThat(v.name()).isEqualTo(hint.variables().get(role));
                        }));
  }

  @Test
  void doesNotCombineDifferentMidpointsInSiblingBlocks() {
    assertThat(
            detect(
                """
        int l=0,r=a.length-1;
        while(l<=r) {
          {int mid=(l+r)/2; if(a[mid]<k) l=mid+1;}
          {int mid=0; if(a[mid]>k) r=mid-1;}
        }
        """))
        .isEmpty();
  }
}
