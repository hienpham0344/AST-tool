package com.example.astchunker.algorithm.twopointers;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.junit.jupiter.api.Test;

class PointerRoleAnalyzerTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private final PointerRoleAnalyzer analyzer = new PointerRoleAnalyzer();

  @Test
  void bindsOpposingArrayAccessesToOneInputAndTwoDeclarations() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          for (int a = 0, b = values.length - 1; a < b; a++, b--) {
            int x = values[a], y = values[b];
          }
        } }
        """);
    Node loop = unit.findFirst(ForStmt.class).orElseThrow();
    assertThat(analyzer.candidates(unit, loop)).singleElement().satisfies(candidate -> {
      assertThat(candidate.dataName()).isEqualTo("values");
      assertThat(candidate.representation()).isEqualTo("INT_ARRAY");
      assertThat(candidate.firstDeclarationId()).isNotEqualTo(candidate.secondDeclarationId());
      assertThat(candidate.accesses()).hasSize(2);
    });
  }

  @Test
  void acceptsFullyQualifiedIntegerListGetAndSet() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(java.util.List<Integer> values) {
          int a = 0, b = values.size() - 1;
          while (a < b) { values.set(a, values.get(b)); a++; b--; }
        } }
        """);
    Node loop = unit.findFirst(WhileStmt.class).orElseThrow();
    assertThat(analyzer.candidates(unit, loop)).singleElement().satisfies(candidate -> {
      assertThat(candidate.representation()).isEqualTo("INTEGER_LIST");
      assertThat(candidate.accesses()).anySatisfy(access ->
          assertThat(access.kind()).isEqualTo("SET"));
      assertThat(candidate.accesses()).anySatisfy(access ->
          assertThat(access.kind()).isEqualTo("GET"));
    });
  }

  @Test
  void rejectsDifferentInputsAndUnsupportedListElementType() {
    CompilationUnit separate = parser.parseCompilationUnit("""
        class Sample { void run(int[] a, int[] b) {
          int l = 0, r = a.length - 1;
          while (l < r) { int x = a[l] + b[r]; l++; r--; }
        } }
        """);
    assertThat(analyzer.candidates(separate, separate.findFirst(WhileStmt.class).orElseThrow()))
        .isEmpty();
    CompilationUnit strings = parser.parseCompilationUnit("""
        class Sample { void run(java.util.List<String> a) {
          int l = 0, r = a.size() - 1;
          while (l < r) { String x = a.get(l) + a.get(r); l++; r--; }
        } }
        """);
    assertThat(analyzer.candidates(strings, strings.findFirst(WhileStmt.class).orElseThrow()))
        .isEmpty();
  }

  @Test
  void rejectsShadowedPointerDeclaration() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] data) {
          int left = 0, right = data.length - 1;
          while (left < right) {
            { int left = right; int x = data[left] + data[right]; }
            left++; right--;
          }
        } }
        """);
    assertThat(analyzer.candidates(unit, unit.findFirst(WhileStmt.class).orElseThrow()))
        .isEmpty();
  }

  @Test
  void resolvesImmutableIndexAliasToOriginalPointer() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] data) {
          int left = 0, right = data.length - 1;
          while (left < right) {
            int position = left;
            int x = data[position] + data[right];
            left++; right--;
          }
        } }
        """);
    assertThat(analyzer.candidates(unit, unit.findFirst(WhileStmt.class).orElseThrow()))
        .singleElement().satisfies(candidate ->
            assertThat(candidate.firstName()).isEqualTo("left"));
  }

  @Test
  void rejectsAliasThatIsReassigned() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] data) {
          int left = 0, right = data.length - 1;
          while (left < right) {
            int position = left;
            position = right;
            int x = data[position] + data[right];
            left++; right--;
          }
        } }
        """);
    assertThat(analyzer.candidates(unit, unit.findFirst(WhileStmt.class).orElseThrow()))
        .isEmpty();
  }

  @Test
  void followsPurePrivateHelperWithFixedTarget() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample {
          private static int at(int[] values, int position) { return values[position]; }
          void run(int[] data) {
            int left = 0, right = data.length - 1;
            while (left < right) { int x = at(data, left) + at(data, right); left++; right--; }
          }
        }
        """);
    assertThat(analyzer.candidates(unit, unit.findFirst(WhileStmt.class).orElseThrow()))
        .singleElement().satisfies(candidate -> assertThat(candidate.accesses()).hasSize(2));
  }

  @Test
  void rejectsOverrideableOrSideEffectingHelper() {
    CompilationUnit dynamic = parser.parseCompilationUnit("""
        class Sample {
          int at(int[] values, int position) { return values[position]; }
          void run(int[] data) {
            int left = 0, right = data.length - 1;
            while (left < right) { int x = at(data, left) + at(data, right); left++; right--; }
          }
        }
        """);
    assertThat(analyzer.candidates(dynamic, dynamic.findFirst(WhileStmt.class).orElseThrow()))
        .isEmpty();
    CompilationUnit sideEffect = parser.parseCompilationUnit("""
        class Sample {
          private static int at(int[] values, int position) {
            values[0] = 7; return values[position];
          }
          void run(int[] data) {
            int left = 0, right = data.length - 1;
            while (left < right) { int x = at(data, left) + at(data, right); left++; right--; }
          }
        }
        """);
    assertThat(analyzer.candidates(sideEffect, sideEffect.findFirst(WhileStmt.class).orElseThrow()))
        .isEmpty();
  }
}
