package com.example.astchunker.algorithm.twopointers;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.TwoPointersPattern;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.junit.jupiter.api.Test;

class PointerProgressAnalyzerTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private final PointerRoleAnalyzer roles = new PointerRoleAnalyzer();
  private final PointerProgressAnalyzer progress = new PointerProgressAnalyzer();

  private PointerProgressAnalyzer.Proof proof(String source, Class<? extends Node> type) {
    CompilationUnit unit = parser.parseCompilationUnit(source);
    Node loop = unit.findAll(Node.class, type::isInstance).get(0);
    return progress.prove(loop, roles.candidates(unit, loop).get(0)).orElseThrow();
  }

  @Test
  void provesOpposingForLoopWithHeaderUpdates() {
    var proof = proof("""
        class Sample { void run(int[] data) {
          for (int left = 0, right = data.length - 1; left < right; left += 1, right = right - 1) {
            int x = data[left] + data[right];
          }
        } }
        """, ForStmt.class);
    assertThat(proof.movement()).isEqualTo(TwoPointersPattern.Movement.OPPOSING);
    assertThat(proof.operationAstNodeIds()).containsKeys("firstUpdate", "secondUpdate");
  }

  @Test
  void provesSameDirectionConditionalCompaction() {
    var proof = proof("""
        class Sample { void run(int[] data) {
          int slow = 0;
          for (int scan = 0; scan < data.length; scan++) {
            if (data[scan] != 0) data[slow++] = data[scan];
          }
        } }
        """, ForStmt.class);
    assertThat(proof.movement()).isEqualTo(TwoPointersPattern.Movement.SAME_DIRECTION);
  }

  @Test
  void rejectsContinuingPathWithoutAnyProgress() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] data, boolean stop) {
          int left = 0, right = data.length - 1;
          while (left < right) {
            int x = data[left] + data[right];
            if (stop) continue;
            left++; right--;
          }
        } }
        """);
    Node loop = unit.findFirst(WhileStmt.class).orElseThrow();
    assertThat(progress.prove(loop, roles.candidates(unit, loop).get(0))).isEmpty();
  }

  @Test
  void acceptsReversedOpposingGuardAndConditionalMovement() {
    var proof = proof("""
        class Sample { void run(int[] data, int target) {
          int left = 0, right = data.length - 1;
          while (right > left) {
            int x = data[left] + data[right];
            if (x < target) left++; else right--;
          }
        } }
        """, WhileStmt.class);
    assertThat(proof.movement()).isEqualTo(TwoPointersPattern.Movement.OPPOSING);
  }

  @Test
  void rejectsConditionalPathWithNoMovement() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] data, boolean a, boolean b) {
          int left = 0, right = data.length - 1;
          while (left < right) {
            int x = data[left] + data[right];
            if (a) left++; else if (b) right--;
          }
        } }
        """);
    Node loop = unit.findFirst(WhileStmt.class).orElseThrow();
    assertThat(progress.prove(loop, roles.candidates(unit, loop).get(0))).isEmpty();
  }

  @Test
  void rejectsWrongDirectionUpdate() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] data) {
          int left = 0, right = data.length - 1;
          while (left < right) { int x = data[left] + data[right]; left--; right--; }
        } }
        """);
    Node loop = unit.findFirst(WhileStmt.class).orElseThrow();
    assertThat(progress.prove(loop, roles.candidates(unit, loop).get(0))).isEmpty();
  }

  @Test
  void requiresEntryGuardForDoWhile() {
    CompilationUnit unsafe = parser.parseCompilationUnit("""
        class Sample { void run(int[] data) {
          int left = 0, right = data.length - 1;
          do { int x = data[left] + data[right]; left++; right--; } while (left < right);
        } }
        """);
    Node unsafeLoop = unsafe.findFirst(DoStmt.class).orElseThrow();
    assertThat(progress.prove(unsafeLoop, roles.candidates(unsafe, unsafeLoop).get(0))).isEmpty();
    var proof = proof("""
        class Sample { void run(int[] data) {
          int left = 0, right = data.length - 1;
          if (data.length < 2) return;
          do { int x = data[left] + data[right]; left++; right--; } while (left < right);
        } }
        """, DoStmt.class);
    assertThat(proof.movement()).isEqualTo(TwoPointersPattern.Movement.OPPOSING);
  }
}
