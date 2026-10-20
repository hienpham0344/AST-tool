package com.example.astchunker.algorithm.linearsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearSearchDetectorTest {
  private List<AlgorithmHint> detect(String source) {
    return new AlgorithmPatternAnalyzer().analyze(new AstAnalyzer().parseCompilationUnit(source));
  }

  @Test
  void recognizesArrayForReturningIndexWithRenamedRoles() {
    var hints = detect("""
        class Sample { int find(int[] numbers, int wanted) {
          for (int cursor = 0; cursor < numbers.length; cursor++) {
            if (wanted == numbers[cursor]) return cursor;
          }
          return -1;
        } }
        """);
    assertThat(hints).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("linear-search");
      assertThat(hint.linearSearch().representation()).isEqualTo("INT_ARRAY");
      assertThat(hint.linearSearch().resultKind()).isEqualTo("INDEX");
      assertThat(hint.variableDeclarationIds()).containsKeys("data", "scan", "target");
    });
  }

  @Test
  void recognizesListWhileReturningBoolean() {
    var hints = detect("""
        class Sample { boolean contains(java.util.List<Integer> items, int needle) {
          int position = 0;
          while (position < items.size()) {
            if (items.get(position) == needle) { return true; }
            position += 1;
          }
          return false;
        } }
        """);
    assertThat(hints).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("linear-search");
      assertThat(hint.linearSearch().representation()).isEqualTo("INTEGER_LIST");
      assertThat(hint.linearSearch().resultKind()).isEqualTo("BOOLEAN");
    });
  }

  @Test
  void rejectsPlainTraversalAndWrongReturn() {
    assertThat(detect("""
        class Sample { int run(int[] a, int target) {
          for (int i=0; i<a.length; i++) { if (a[i] == target) System.out.println(i); }
          return -1;
        } }
        """)).isEmpty();
    assertThat(detect("""
        class Sample { int run(int[] a, int target) {
          for (int i=0; i<a.length; i++) { if (a[i] == target) return 0; }
          return -1;
        } }
        """)).isEmpty();
  }

  @Test
  void rejectsSkippedComparisonAndMutatedInput() {
    assertThat(detect("""
        class Sample { int run(int[] a, int target, boolean enabled) {
          for (int i=0; i<a.length; i++) {
            if (enabled) { if (a[i] == target) return i; }
          }
          return -1;
        } }
        """)).isEmpty();
    assertThat(detect("""
        class Sample { int run(int[] a, int target) {
          for (int i=0; i<a.length; i++) {
            a[i] = 0;
            if (a[i] == target) return i;
          }
          return -1;
        } }
        """)).isEmpty();
  }

  @Test
  void rejectsPointerJumpAndDifferentInputs() {
    assertThat(detect("""
        class Sample { int run(int[] a, int target) {
          for (int i=0; i<a.length; i+=2) { if (a[i] == target) return i; }
          return -1;
        } }
        """)).isEmpty();
    assertThat(detect("""
        class Sample { int run(int[] a, int[] b, int target) {
          for (int i=0; i<a.length; i++) { if (b[i] == target) return i; }
          return -1;
        } }
        """)).isEmpty();
  }

  @Test
  void rejectsTargetCreatedInsideScanAndEarlyThrow() {
    assertThat(detect("""
        class Sample { int run(int[] a) {
          for (int i=0; i<a.length; i++) {
            int target = a[i];
            if (a[i] == target) return i;
          }
          return -1;
        } }
        """)).isEmpty();
    assertThat(detect("""
        class Sample { int run(int[] a, int target) {
          for (int i=0; i<a.length; i++) {
            if (i == 0) throw new IllegalStateException();
            if (a[i] == target) return i;
          }
          return -1;
        } }
        """)).isEmpty();
  }

  @Test
  void rejectsAliasWritesAndUnknownConstructors() {
    assertThat(detect("""
        class Sample { int run(int[] data, int target) {
          int[] alias = data;
          for (int i=0; i<data.length; i++) {
            alias[i] = target;
            if (data[i] == target) return i;
          }
          return -1;
        } }
        """)).isEmpty();
    assertThat(detect("""
        class Sample {
          static class Mutator { Mutator(int[] a) { a[0] = 5; } }
          int run(int[] data, int target) {
            for (int i=0; i<data.length; i++) {
              new Mutator(data);
              if (data[i] == target) return i;
            }
            return -1;
          }
        }
        """)).isEmpty();
  }
}
