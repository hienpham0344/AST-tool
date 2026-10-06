package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import org.junit.jupiter.api.Test;

class InsertionSortDetectorTest {
  private java.util.List<com.example.astchunker.model.AlgorithmHint> detect(String body) {
    var analysis =
        new AstAnalyzer()
            .analyze("public class Sample { void sort(int[] values) {\n" + body + "\n} }");
    return new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
  }

  @Test
  void bindsAscendingInsertionRolesAndMutationLocations() {
    var hints =
        detect(
            """
            for (int i = 1; i < values.length; i++) {
              int key = values[i];
              int j = i - 1;
              while (j >= 0 && values[j] > key) {
                values[j + 1] = values[j];
                j--;
              }
              values[j + 1] = key;
            }
            """);

    assertThat(hints).hasSize(1);
    var hint = hints.get(0);
    assertThat(hint.type()).isEqualTo("insertion-sort");
    assertThat(hint.variables()).containsEntry("array", "values").containsEntry("key", "key");
    assertThat(hint.variableDeclarationIds().keySet())
        .containsExactlyInAnyOrder("array", "pass", "scan", "key");
    assertThat(hint.sort().direction())
        .isEqualTo(com.example.astchunker.model.SortPattern.Direction.ASCENDING);
    assertThat(hint.sort().swapStatementAstNodeIds()).isEmpty();
    assertThat(hint.sort().operationAstNodeIds().keySet())
        .containsExactlyInAnyOrder("shiftWrite", "scanDecrement", "insertWrite");
  }

  @Test
  void supportsDescendingOrderAndReversedComparisonOperands() {
    var hints =
        detect(
            """
            for (int position = 1; position < values.length; position++) {
              int current = values[position];
              int cursor = position - 1;
              while (cursor >= 0 && current > values[cursor]) {
                values[cursor + 1] = values[cursor];
                --cursor;
              }
              values[cursor + 1] = current;
            }
            """);

    assertThat(hints).hasSize(1);
    assertThat(hints.get(0).type()).isEqualTo("insertion-sort");
    assertThat(hints.get(0).sort().direction())
        .isEqualTo(com.example.astchunker.model.SortPattern.Direction.DESCENDING);
    assertThat(hints.get(0).variables())
        .containsEntry("pass", "position")
        .containsEntry("scan", "cursor")
        .containsEntry("key", "current");
  }

  @Test
  void rejectsUnsafeBoundaryOrderWrongShiftAndNonIntArrays() {
    var unsafe =
        detect(
            """
            for (int i = 1; i < values.length; i++) {
              int key = values[i]; int j = i - 1;
              while (values[j] > key && j >= 0) { values[j + 1] = values[j]; j--; }
              values[j + 1] = key;
            }
            """);
    var wrongShift =
        detect(
            """
            for (int i = 1; i < values.length; i++) {
              int key = values[i]; int j = i - 1;
              while (j >= 0 && values[j] > key) { values[j] = values[j + 1]; j--; }
              values[j + 1] = key;
            }
            """);
    var nonInt =
        new AstAnalyzer()
            .analyze(
                "public class Sample { void sort(long[] values) {\n"
                    + "for (int i = 1; i < values.length; i++) { int key = (int) values[i]; "
                    + "int j = i - 1; while (j >= 0 && values[j] > key) { "
                    + "values[j + 1] = values[j]; j--; } values[j + 1] = key; }\n} }");

    assertThat(unsafe).isEmpty();
    assertThat(wrongShift).isEmpty();
    assertThat(new AlgorithmPatternAnalyzer().analyze(nonInt.compilationUnit())).isEmpty();
  }
}
