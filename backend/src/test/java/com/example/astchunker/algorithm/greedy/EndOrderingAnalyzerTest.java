package com.example.astchunker.algorithm.greedy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.ActivitySelectionPattern.OrderingEvidence;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.junit.jupiter.api.Test;

class EndOrderingAnalyzerTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private final EndOrderingAnalyzer analyzer = new EndOrderingAnalyzer();

  @Test
  void treatsUnsortedSourceAsInputPrecondition() {
    var unit = parser.parseCompilationUnit("""
        class Sample {
          int select(int[][] data) {
            int last = Integer.MIN_VALUE, count = 0;
            for (int i = 0; i < data.length; i++) {
              int[] x = data[i];
              if (x[0] >= last) { count++; last = x[1]; }
            }
            return count;
          }
        }
        """);
    var loop = unit.findFirst(com.github.javaparser.ast.stmt.ForStmt.class).orElseThrow();
    var result = analyzer.classify(unit, loop, IntervalRoles.resolve(unit, loop).orElseThrow());
    assertThat(result.evidence()).isEqualTo(OrderingEvidence.INPUT_PRECONDITION);
    assertThat(result.sortAstNodeId()).isNull();
  }

  @Test
  void provesAscendingEndComparatorOnSameListBeforeScan() {
    var unit = parser.parseCompilationUnit("""
        import java.util.Comparator;
        import java.util.List;
        class Sample {
          record Interval(int start, int end) {}
          int select(List<Interval> events) {
            events.sort(Comparator.comparingInt(event -> event.end()));
            int last = Integer.MIN_VALUE, count = 0;
            for (Interval event : events) {
              if (event.start() >= last) { count++; last = event.end(); }
            }
            return count;
          }
        }
        """);
    var loop = unit.findFirst(ForEachStmt.class).orElseThrow();
    var roles = IntervalRoles.resolve(unit, loop).orElseThrow();
    var result = analyzer.classify(unit, loop, roles);
    assertThat(result.evidence()).isEqualTo(OrderingEvidence.SOURCE_PROVEN);
    assertThat(result.sortAstNodeId()).isNotBlank();
  }

  @Test
  void provesCollectionsSortWithRecordMethodReference() {
    var unit = parser.parseCompilationUnit("""
        import java.util.Collections;
        import java.util.Comparator;
        import java.util.List;
        class Sample {
          record Slot(int from, int until) {}
          int select(List<Slot> data) {
            Collections.sort(data, Comparator.comparingInt(Slot::until));
            int last = Integer.MIN_VALUE, count = 0;
            for (Slot slot : data) {
              if (slot.from() >= last) { count++; last = slot.until(); }
            }
            return count;
          }
        }
        """);
    var loop = unit.findFirst(ForEachStmt.class).orElseThrow();
    var roles = IntervalRoles.resolve(unit, loop).orElseThrow();
    assertThat(analyzer.classify(unit, loop, roles).evidence())
        .isEqualTo(OrderingEvidence.SOURCE_PROVEN);
  }

  @Test
  void doesNotProveDescendingOrOverflowProneComparators() {
    for (String comparator : new String[] {
        "(a, b) -> Integer.compare(b[1], a[1])",
        "(a, b) -> a[1] - b[1]"
    }) {
      var unit = parser.parseCompilationUnit("""
          import java.util.Arrays;
          class Sample {
            int select(int[][] data) {
              Arrays.sort(data, %s);
              int last = Integer.MIN_VALUE, count = 0;
              for (int i = 0; i < data.length; i++) {
                int[] x = data[i];
                if (x[0] >= last) { count++; last = x[1]; }
              }
              return count;
            }
          }
          """.formatted(comparator));
      var loop = unit.findFirst(com.github.javaparser.ast.stmt.ForStmt.class).orElseThrow();
      var roles = IntervalRoles.resolve(unit, loop).orElseThrow();
      assertThat(analyzer.classify(unit, loop, roles).evidence())
          .isNotEqualTo(OrderingEvidence.SOURCE_PROVEN);
    }
  }

  @Test
  void doesNotProveConditionalOrInvalidatedSort() {
    String source = """
        import java.util.Arrays;
        class Sample {
          int select(int[][] data, boolean shouldSort) {
            Arrays.sort(data, (a, b) -> Integer.compare(a[1], b[1]));
            int last = Integer.MIN_VALUE, count = 0;
            for (int i = 0; i < data.length; i++) {
              int[] x = data[i];
              if (x[0] >= last) { count++; last = x[1]; }
            }
            return count;
          }
        }
        """;
    for (String variant : new String[] {
        source.replace("Arrays.sort(data, (a, b) -> Integer.compare(a[1], b[1]));",
            "if (shouldSort) Arrays.sort(data, (a, b) -> Integer.compare(a[1], b[1]));"),
        source.replace("int last =", "data[0][1] = 0; int last =")
    }) {
      var unit = parser.parseCompilationUnit(variant);
      var loop = unit.findFirst(com.github.javaparser.ast.stmt.ForStmt.class).orElseThrow();
      var roles = IntervalRoles.resolve(unit, loop).orElseThrow();
      assertThat(analyzer.classify(unit, loop, roles).evidence())
          .as(variant).isNotEqualTo(OrderingEvidence.SOURCE_PROVEN);
    }
  }

  @Test
  void doesNotTreatUserClassesNamedLikeJavaUtilitiesAsProof() {
    String source = """
        class Sample {
          static class Arrays { static void sort(int[][] value, java.util.Comparator<int[]> ignored) {} }
          static class Integer { static int compare(int a, int b) { return 0; } }
          int select(int[][] data) {
            Arrays.sort(data, (a, b) -> Integer.compare(a[1], b[1]));
            int last = java.lang.Integer.MIN_VALUE, count = 0;
            for (int i = 0; i < data.length; i++) {
              int[] x = data[i];
              if (x[0] >= last) { count++; last = x[1]; }
            }
            return count;
          }
        }
        """;
    var unit = parser.parseCompilationUnit(source);
    var loop = unit.findFirst(com.github.javaparser.ast.stmt.ForStmt.class).orElseThrow();
    var roles = IntervalRoles.resolve(unit, loop).orElseThrow();
    assertThat(analyzer.classify(unit, loop, roles).evidence())
        .isEqualTo(OrderingEvidence.INPUT_PRECONDITION);
  }
}
