package com.example.astchunker.algorithm.sort;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.SortPattern.Direction;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeapSiftAnalyzerTest {
  private static final String DOWN =
      """
      class Sample {
        private static void sift(int[] values, int limit, int cursor) {
          while (2 * cursor + 1 < limit) {
            int left = 2 * cursor + 1;
            int right = left + 1;
            int chosen = left;
            if (right < limit && values[right] > values[left]) chosen = right;
            if (values[cursor] >= values[chosen]) break;
            int saved = values[cursor];
            values[cursor] = values[chosen];
            values[chosen] = saved;
            cursor = chosen;
          }
        }
      }
      """;
  private static final String RECURSIVE =
      """
      class Sample {
        private static void sift(int[] values, int limit, int cursor) {
          int left = 2 * cursor + 1;
          int right = left + 1;
          int chosen = cursor;
          if (left < limit && values[left] > values[chosen]) chosen = left;
          if (right < limit && values[right] > values[chosen]) chosen = right;
          if (chosen != cursor) {
            int saved = values[cursor];
            values[cursor] = values[chosen];
            values[chosen] = saved;
            sift(values, limit, chosen);
          }
        }
      }
      """;
  private static final String UP =
      """
      class Sample {
        private static void lift(int[] values, int limit, int cursor) {
          while (cursor > 0) {
            int parent = (cursor - 1) / 2;
            if (values[parent] >= values[cursor]) break;
            int saved = values[parent];
            values[parent] = values[cursor];
            values[cursor] = saved;
            cursor = parent;
          }
        }
      }
      """;

  @Test
  void provesIterativeRecursiveAndUpwardSifts() {
    assertThat(proof(DOWN, "sift", Direction.ASCENDING))
        .get()
        .satisfies(p -> assertThat(p.kind()).isEqualTo(HeapSiftAnalyzer.Kind.DOWN));
    assertThat(proof(RECURSIVE, "sift", Direction.ASCENDING))
        .get()
        .satisfies(p -> assertThat(p.kind()).isEqualTo(HeapSiftAnalyzer.Kind.DOWN));
    assertThat(proof(UP, "lift", Direction.ASCENDING))
        .get()
        .satisfies(p -> assertThat(p.kind()).isEqualTo(HeapSiftAnalyzer.Kind.UP));
    assertThat(
            proof(DOWN.replace(" > ", " < ").replace(" >= ", " <= "), "sift", Direction.DESCENDING))
        .isPresent();
    assertThat(
            proof(
                DOWN.replace("values[right] > values[left]", "values[left] < values[right]")
                    .replace(
                        "values[cursor] >= values[chosen]", "values[chosen] <= values[cursor]"),
                "sift",
                Direction.ASCENDING))
        .isPresent();
    assertThat(
            proof(
                DOWN.replace("2 * cursor + 1", "cursor * 2 + 1")
                    .replace("int right = left + 1", "int right = cursor * 2 + 2"),
                "sift",
                Direction.ASCENDING))
        .isPresent();
  }

  @Test
  void rejectsNearMisses() {
    for (String changed :
        List.of(
            DOWN.replace("int right = left + 1", "int right = left + 2"),
            DOWN.replace("values[right] > values[left]", "values[right] < values[left]"),
            DOWN.replace("values[chosen] = saved;", "values[chosen] = 0;"),
            DOWN.replace("cursor = chosen;", "cursor = cursor;"),
            DOWN.replace("values[chosen] = saved;", "values[limit - 1] = saved;"),
            RECURSIVE.replace(
                "if (right < limit && values[right] > values[chosen]) chosen = right;", ""),
            UP.replace("cursor = parent;", "cursor = cursor;"))) {
      assertThat(proof(changed, changed.contains("lift(") ? "lift" : "sift", Direction.ASCENDING))
          .as(changed)
          .isEmpty();
    }
  }

  @Test
  void provesReorderedNonOverridableHelperButRejectsForeignOrOverridableCall() {
    String source =
        DOWN.replace(
                "private static void sift(int[] values, int limit, int cursor)",
                "private static void descend(int cursor, int[] values, int limit)")
            .replace(
                "class Sample {",
                "class Sample {\n"
                    + "  private static void sort(int[] data, int size, int root) { descend(root, data, size); }");
    var unit = new AstAnalyzer().parseCompilationUnit(source);
    var owner = unit.getClassByName("Sample").orElseThrow();
    var sort = owner.getMethodsByName("sort").get(0);
    var call = sort.findFirst(MethodCallExpr.class).orElseThrow();
    assertThat(
            HeapSiftAnalyzer.prove(
                call,
                sort,
                AstAnalyzer.nodeId(sort.getParameter(0)),
                Direction.ASCENDING,
                AstAnalyzer.nodeId(sort.getParameter(1))))
        .isPresent();

    assertThat(
            helperProof(
                source
                    .replace("private static void descend", "public void descend")
                    .replace("private static void sort", "private void sort")))
        .isEmpty();
    assertThat(
            helperProof(
                source.replace(
                    "descend(root, data, size)",
                    "Sample other = new Sample(); other.descend(root, data, size)")))
        .isEmpty();
    assertThat(
            helperProof(
                source.replace(
                    "descend(root, data, size)",
                    "Sample Sample = new Sample(); Sample.descend(root, data, size)")))
        .isEmpty();
    assertThat(
            helperProof(
                source.replace("descend(root, data, size)", "descend(root, new int[2], size)")))
        .isEmpty();
  }

  private static java.util.Optional<HeapSiftAnalyzer.HeapSiftProof> helperProof(String source) {
    var unit = new AstAnalyzer().parseCompilationUnit(source);
    var sort = unit.getClassByName("Sample").orElseThrow().getMethodsByName("sort").get(0);
    return HeapSiftAnalyzer.prove(
        sort.findFirst(MethodCallExpr.class).orElseThrow(),
        sort,
        AstAnalyzer.nodeId(sort.getParameter(0)),
        Direction.ASCENDING,
        AstAnalyzer.nodeId(sort.getParameter(1)));
  }

  private static java.util.Optional<HeapSiftAnalyzer.HeapSiftProof> proof(
      String source, String name, Direction direction) {
    ClassOrInterfaceDeclaration owner =
        new AstAnalyzer().parseCompilationUnit(source).getClassByName("Sample").orElseThrow();
    MethodDeclaration method = owner.getMethodsByName(name).get(0);
    return HeapSiftAnalyzer.prove(
        method.getBody().orElseThrow(),
        method,
        AstAnalyzer.nodeId(method.getParameter(0)),
        direction,
        AstAnalyzer.nodeId(method.getParameter(1)));
  }
}
