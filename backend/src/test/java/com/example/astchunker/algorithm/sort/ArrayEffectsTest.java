package com.example.astchunker.algorithm.sort;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.junit.jupiter.api.Test;

class ArrayEffectsTest {
  private static final String DIRECT =
      "class Sample { void sort(int[] data, int scan) { if (data[scan] > data[scan + 1]) { "
          + "int saved = data[scan]; data[scan] = data[scan + 1]; data[scan + 1] = saved; } } }";

  @Test
  void recognizesOrderedThreeStatementSwap() {
    BlockStmt body =
        (BlockStmt) StaticJavaParser.parse(DIRECT).findFirst(IfStmt.class).orElseThrow().getThenStmt();
    var swap = ArrayEffects.temporarySwap(body.getStatements(), "data", "scan", "scan + 1").orElseThrow();
    assertThat(swap.tempName()).isEqualTo("saved");
    assertThat(swap.operationAstNodeIds()).hasSize(3).doesNotHaveDuplicates();
    assertThat(swap.helperMethodAstNodeId()).isNull();
  }

  @Test
  void rejectsIncompleteSwapOrExtraArrayWrite() {
    String incomplete = DIRECT.replace("data[scan + 1] = saved", "data[scan + 1] = data[scan]");
    String extra = DIRECT.replace("data[scan + 1] = saved;", "data[scan + 1] = saved; data[0] = 0;");
    for (String source : new String[] {incomplete, extra}) {
      BlockStmt body =
          (BlockStmt) StaticJavaParser.parse(source).findFirst(IfStmt.class).orElseThrow().getThenStmt();
      assertThat(ArrayEffects.temporarySwap(body.getStatements(), "data", "scan", "scan + 1"))
          .isEmpty();
    }
  }

  @Test
  void summarizesSimpleSameFileSwapHelper() {
    String source =
        "class Sample { void sort(int[] data, int scan) { swap(data, scan, scan + 1); } "
            + "void swap(int[] values, int left, int right) { int saved = values[left]; "
            + "values[left] = values[right]; values[right] = saved; } }";
    Statement call =
        StaticJavaParser.parse(source).findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class)
            .orElseThrow()
            .findAncestor(Statement.class)
            .orElseThrow();
    var swap = ArrayEffects.helperSwap(call, "data", "scan", "scan + 1").orElseThrow();
    assertThat(swap.operationAstNodeIds()).hasSize(3).doesNotHaveDuplicates();
    assertThat(swap.helperMethodAstNodeId()).isNotBlank();
  }

  @Test
  void rejectsHelperWithUnrelatedSideEffect() {
    String source =
        "class Sample { void sort(int[] data, int scan) { swap(data, scan, scan + 1); } "
            + "void swap(int[] values, int left, int right) { int saved = values[left]; "
            + "values[left] = values[right]; values[right] = saved; values[0] = 0; } }";
    Statement call =
        StaticJavaParser.parse(source).findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class)
            .orElseThrow()
            .findAncestor(Statement.class)
            .orElseThrow();
    assertThat(ArrayEffects.helperSwap(call, "data", "scan", "scan + 1")).isEmpty();
  }
}
