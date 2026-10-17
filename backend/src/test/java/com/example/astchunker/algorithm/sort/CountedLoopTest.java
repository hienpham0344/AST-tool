package com.example.astchunker.algorithm.sort;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.junit.jupiter.api.Test;

class CountedLoopTest {
  @Test
  void forAndWhileExposeTheSameCounterAndLogicalBody() {
    Node forLoop =
        StaticJavaParser.parse("class Sample { void run(int[] data) { for (int scan = 0; scan < data.length; scan++) { data[scan] = 0; } } }")
            .findFirst(ForStmt.class)
            .orElseThrow();
    Node whileLoop =
        StaticJavaParser.parse("class Sample { void run(int[] data) { int scan = 0; while (scan < data.length) { data[scan] = 0; scan++; } } }")
            .findFirst(WhileStmt.class)
            .orElseThrow();

    var forFacts = CountedLoop.match(forLoop, "0").orElseThrow();
    var whileFacts = CountedLoop.match(whileLoop, "0").orElseThrow();
    assertThat(forFacts.counter()).isEqualTo("scan");
    assertThat(whileFacts.counter()).isEqualTo("scan");
    assertThat(forFacts.counterDeclarationId()).isNotBlank();
    assertThat(whileFacts.counterDeclarationId()).isNotBlank();
    assertThat(forFacts.bodyStatements()).hasSize(1);
    assertThat(whileFacts.bodyStatements()).hasSize(1);
  }

  @Test
  void rejectsConditionalOrAdditionalCounterWrite() {
    String conditional =
        "class Sample { void run(int[] data) { int scan = 0; while (scan < data.length) { data[scan] = 0; if (data[scan] > 0) scan++; } } }";
    String additional =
        "class Sample { void run(int[] data) { int scan = 0; while (scan < data.length) { scan += 2; data[scan] = 0; scan++; } } }";
    assertThat(CountedLoop.match(StaticJavaParser.parse(conditional).findFirst(WhileStmt.class).orElseThrow(), "0"))
        .isEmpty();
    assertThat(CountedLoop.match(StaticJavaParser.parse(additional).findFirst(WhileStmt.class).orElseThrow(), "0"))
        .isEmpty();
  }
}
