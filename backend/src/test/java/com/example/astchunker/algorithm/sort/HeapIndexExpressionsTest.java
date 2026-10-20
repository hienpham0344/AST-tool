package com.example.astchunker.algorithm.sort;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.junit.jupiter.api.Test;

class HeapIndexExpressionsTest {
  @Test
  void recognizesEquivalentChildrenAndParent() {
    for (String left : new String[] {"2 * i + 1", "i * 2 + 1", "(i << 1) + 1"}) {
      assertThat(HeapIndexExpressions.child(StaticJavaParser.parseExpression(left), "i", null))
          .contains(HeapIndexExpressions.ChildSide.LEFT);
    }
    for (String right : new String[] {"2 * i + 2", "i * 2 + 2", "(i << 1) + 2", "left + 1"}) {
      assertThat(HeapIndexExpressions.child(StaticJavaParser.parseExpression(right), "i", "left"))
          .contains(HeapIndexExpressions.ChildSide.RIGHT);
    }
    assertThat(HeapIndexExpressions.parent(StaticJavaParser.parseExpression("(i - 1) / 2"), "i"))
        .isTrue();
  }

  @Test
  void rejectsIncorrectOrEffectfulIndices() {
    for (String wrong :
        new String[] {"2 * i", "2 * i + 3", "left + 2", "2 * next() + 1", "++i * 2 + 1"}) {
      assertThat(HeapIndexExpressions.child(StaticJavaParser.parseExpression(wrong), "i", "left"))
          .isEmpty();
    }
    assertThat(HeapIndexExpressions.child(StaticJavaParser.parseExpression("left + 1"), "i", null))
        .isEmpty();
    assertThat(HeapIndexExpressions.parent(StaticJavaParser.parseExpression("i / 2"), "i"))
        .isFalse();
  }

  @Test
  void normalizesExclusiveAndInclusiveHeapEnds() {
    assertThat(
            HeapIndexExpressions.boundary(StaticJavaParser.parseExpression("size"), "size", false))
        .contains(new HeapIndexExpressions.HeapBoundary("size"));
    assertThat(
            HeapIndexExpressions.boundary(
                StaticJavaParser.parseExpression("size - 1"), "size", true))
        .contains(new HeapIndexExpressions.HeapBoundary("size"));
    assertThat(
            HeapIndexExpressions.boundary(StaticJavaParser.parseExpression("last"), "last", true))
        .contains(new HeapIndexExpressions.HeapBoundary("last + 1"));
    assertThat(
            HeapIndexExpressions.boundary(
                StaticJavaParser.parseExpression("size + 1"), "size", false))
        .isEmpty();
  }

  @Test
  void acceptsOnlyUnchangedOneHopArrayAlias() {
    String safe =
        "class Sample { void sort(int[] data) { int[] heap = data; int value = heap[0]; } }";
    var safeUnit = new AstAnalyzer().parseCompilationUnit(safe);
    NameExpr safeUse =
        safeUnit.findFirst(ArrayAccessExpr.class).orElseThrow().getName().asNameExpr();
    assertThat(HeapIndexExpressions.arrayDeclarationId(safeUse))
        .contains(
            AstAnalyzer.nodeId(
                safeUnit.findFirst(com.github.javaparser.ast.body.Parameter.class).orElseThrow()));

    String changed =
        "class Sample { void sort(int[] data, int[] other) { int[] heap = data; heap = other; int value = heap[0]; } }";
    var changedUnit = new AstAnalyzer().parseCompilationUnit(changed);
    NameExpr changedUse =
        changedUnit.findFirst(ArrayAccessExpr.class).orElseThrow().getName().asNameExpr();
    assertThat(HeapIndexExpressions.arrayDeclarationId(changedUse)).isEmpty();
  }
}
