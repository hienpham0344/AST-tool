package com.example.astchunker.algorithm.greedy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.junit.jupiter.api.Test;

class IntervalRolesTest {
  private final AstAnalyzer parser = new AstAnalyzer();

  @Test
  void infersSwappedArrayColumnsFromDecisionAndUpdate() {
    var unit = parser.parseCompilationUnit("""
        class Sample {
          int solve(int[][] slots) {
            int last = Integer.MIN_VALUE, count = 0;
            for (int i = 0; i < slots.length; i++) {
              int[] candidate = slots[i];
              if (candidate[1] >= last) {
                count++;
                last = candidate[0];
              }
            }
            return count;
          }
        }
        """);
    var roles = IntervalRoles.resolve(unit, unit.findFirst(ForStmt.class).orElseThrow()).orElseThrow();
    assertThat(roles.representation()).isEqualTo("INT_MATRIX");
    assertThat(roles.startColumn()).isEqualTo(1);
    assertThat(roles.endColumn()).isEqualTo(0);
    assertThat(roles.inputDeclarationId()).isNotBlank();
  }

  @Test
  void resolvesRecordAccessorsWithoutDependingOnNamesOfVariables() {
    var unit = parser.parseCompilationUnit("""
        import java.util.List;
        class Sample {
          record Interval(int begin, int finish) {}
          int solve(List<Interval> windows) {
            int boundary = Integer.MIN_VALUE, count = 0;
            for (Interval item : windows) {
              if (item.begin() >= boundary) {
                count++;
                boundary = item.finish();
              }
            }
            return count;
          }
        }
        """);
    var roles = IntervalRoles.resolve(unit,
        unit.findFirst(com.github.javaparser.ast.stmt.ForEachStmt.class).orElseThrow())
        .orElseThrow();
    assertThat(roles.representation()).isEqualTo("INTERVAL_LIST");
    assertThat(roles.startField()).isEqualTo("begin");
    assertThat(roles.endField()).isEqualTo("finish");
    assertThat(roles.inputDeclarationId()).isNotBlank();
  }

  @Test
  void resolvesPureAccessorsToFinalFields() {
    var unit = parser.parseCompilationUnit("""
        import java.util.List;
        class Sample {
          static final class Slot {
            final int begin;
            final int finish;
            Slot(int begin, int finish) { this.begin = begin; this.finish = finish; }
            int left() { return this.begin; }
            int right() { return finish; }
          }
          int select(List<Slot> data) {
            int last = Integer.MIN_VALUE, count = 0;
            for (Slot current : data) {
              if (current.left() >= last) { count++; last = current.right(); }
            }
            return count;
          }
        }
        """);
    var roles = IntervalRoles.resolve(unit, unit.findFirst(ForEachStmt.class).orElseThrow())
        .orElseThrow();
    assertThat(roles.startField()).isEqualTo("begin");
    assertThat(roles.endField()).isEqualTo("finish");
    assertThat(roles.startReadName()).isEqualTo("left");
    assertThat(roles.endReadName()).isEqualTo("right");
  }

  @Test
  void rejectsMutableIntervalAndAccessorWithEffects() {
    String source = """
        import java.util.List;
        class Sample {
          static final class Slot {
            int begin;
            final int finish = 1;
            int left() { return begin; }
            int right() { return finish; }
          }
          int select(List<Slot> data) {
            int last = Integer.MIN_VALUE, count = 0;
            for (Slot item : data) {
              if (item.left() >= last) { count++; last = item.right(); }
            }
            return count;
          }
        }
        """;
    var mutable = parser.parseCompilationUnit(source);
    assertThat(IntervalRoles.resolve(mutable, mutable.findFirst(ForEachStmt.class).orElseThrow()))
        .isEmpty();
    var impure = parser.parseCompilationUnit("""
        import java.util.List;
        class Sample {
          record Slot(int begin, int finish) {
            public int begin() { System.out.println(begin); return begin; }
          }
          int select(List<Slot> data) {
            int last = Integer.MIN_VALUE, count = 0;
            for (Slot item : data) {
              if (item.begin() >= last) { count++; last = item.finish(); }
            }
            return count;
          }
        }
        """);
    assertThat(IntervalRoles.resolve(impure, impure.findFirst(ForEachStmt.class).orElseThrow()))
        .isEmpty();
  }
}
