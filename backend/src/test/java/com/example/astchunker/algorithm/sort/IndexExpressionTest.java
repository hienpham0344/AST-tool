package com.example.astchunker.algorithm.sort;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

class IndexExpressionTest {
  @Test
  void equivalentIntegerBoundsHaveTheSameMeaning() {
    assertThat(
            IndexExpression.sameCondition(
                StaticJavaParser.parseExpression("scan + 1 < data.length - pass"),
                StaticJavaParser.parseExpression("scan < data.length - 1 - pass")))
        .isTrue();
    assertThat(
            IndexExpression.sameCondition(
                StaticJavaParser.parseExpression("scan <= data.length - 2 - pass"),
                StaticJavaParser.parseExpression("scan < data.length - 1 - pass")))
        .isTrue();
  }

  @Test
  void rejectsDifferentOrNonlinearBounds() {
    assertThat(
            IndexExpression.sameCondition(
                StaticJavaParser.parseExpression("scan + 2 < data.length - pass"),
                StaticJavaParser.parseExpression("scan < data.length - 1 - pass")))
        .isFalse();
    assertThat(
            IndexExpression.sameCondition(
                StaticJavaParser.parseExpression("scan * 2 < data.length - pass"),
                StaticJavaParser.parseExpression("scan < data.length - 1 - pass")))
        .isFalse();
  }
}
