package com.example.astchunker.algorithm.sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SortPatternRegistryTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private static AlgorithmHint hint(String type) {
    return new AlgorithmHint(type,0.95,"array",List.of(),Map.of(),1,1,"loop","method",Map.of());
  }

  @Test void evaluatesAllDetectorsAndDeduplicatesEquivalentCandidates() {
    var unit = parser.parseCompilationUnit("class S{void f(){for(int i=0;i<3;i++){}}}");
    var loop = unit.findFirst(ForStmt.class).orElseThrow();
    AtomicInteger calls = new AtomicInteger();
    SortPatternDetector first = node -> {calls.incrementAndGet(); return Optional.of(hint("bubbleSort"));};
    SortPatternDetector duplicate = node -> {calls.incrementAndGet(); return Optional.of(hint("bubbleSort"));};
    SortPatternDetector absent = node -> {calls.incrementAndGet(); return Optional.empty();};
    var registry = new SortPatternRegistry(List.of(first,duplicate,absent));
    var result = registry.begin(unit).evaluate(loop);
    assertThat(result.status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(result.candidates()).containsExactly(hint("bubbleSort"));
    assertThat(calls).hasValue(3);
    assertThat(registry.detect(loop)).contains(hint("bubbleSort"));
    assertThat(calls).hasValue(4);
  }

  @Test void ambiguityDoesNotDependOnDetectorOrderAndUnknownHasStableReason() {
    var unit = parser.parseCompilationUnit("class S{void f(){for(;;){}}}");
    var loop = unit.findFirst(ForStmt.class).orElseThrow();
    SortPatternDetector first = node -> Optional.of(hint("bubbleSort"));
    SortPatternDetector second = node -> Optional.of(hint("selectionSort"));
    var a = new SortPatternRegistry(List.of(first,second)).begin(unit).evaluate(loop);
    var b = new SortPatternRegistry(List.of(second,first)).begin(unit).evaluate(loop);
    assertThat(a.status()).isEqualTo(SortPatternRegistry.Status.AMBIGUOUS);
    assertThat(b.status()).isEqualTo(a.status());
    assertThat(a.candidates()).containsExactlyInAnyOrderElementsOf(b.candidates());
    assertThat(a.limitations()).containsExactly("multiple distinct sort patterns matched");
    assertThat(b.limitations()).isEqualTo(a.limitations());
    var session = new SortPatternRegistry(List.of(node -> Optional.empty())).begin(unit);
    var unknown = session.evaluate(loop);
    assertThat(unknown.status()).isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    assertThat(unknown.candidates()).isEmpty();
    assertThat(unknown.limitations()).containsExactly("no sort detector matched");
    assertThat(session.evaluate(loop)).isEqualTo(unknown);
  }

  @Test void reusesFactsAndHelpersInSemanticOverloadForEveryLoop() {
    var unit = parser.parseCompilationUnit("class S{void f(int[] a,int j){for(;;){swap(a,j,j+1);} for(;;){}} private static void swap(int[] a,int i,int j){int t=a[i];a[i]=a[j];a[j]=t;}}");
    AtomicInteger semanticCalls = new AtomicInteger();
    SortPatternDetector detector = new SortPatternDetector() {
      @Override public Optional<AlgorithmHint> detect(Node node) { throw new AssertionError("Session must use semantic overload"); }
      @Override public Optional<AlgorithmHint> detect(Node node, SortPatternRegistry.Session session) {
        semanticCalls.incrementAndGet();
        assertThat(session.facts()).isNotNull();
        assertThat(session.helpers()).isNotNull();
        var result = session.helpers().resolveExchange(unit.findFirst(MethodCallExpr.class).orElseThrow());
        assertThat(result.status()).isEqualTo(SameFileHelperSummaries.Status.RESOLVED);
        return Optional.of(hint("bubbleSort"));
      }
    };
    var session = new SortPatternRegistry(List.of(detector)).begin(unit);
    var facts = session.facts();
    var helpers = session.helpers();
    for (ForStmt loop : unit.findAll(ForStmt.class)) assertThat(session.evaluate(loop).status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(session.facts()).isSameAs(facts);
    assertThat(session.helpers()).isSameAs(helpers);
    assertThat(semanticCalls).hasValue(2);
  }

  @Test void sessionRejectsLoopFromAnotherCompilationUnitEvenWithSameNodeId() {
    String source = "class S{void f(){for(int i=0;i<3;i++){}}}";
    var first = parser.parseCompilationUnit(source);
    var second = parser.parseCompilationUnit(source);
    var foreignLoop = second.findFirst(ForStmt.class).orElseThrow();
    AtomicInteger calls = new AtomicInteger();
    var session = new SortPatternRegistry(List.of(node -> {
      calls.incrementAndGet();
      return Optional.of(hint("bubbleSort"));
    })).begin(first);
    assertThatThrownBy(() -> session.evaluate(foreignLoop)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Compilation unit");
    assertThat(calls).hasValue(0);
  }
}
