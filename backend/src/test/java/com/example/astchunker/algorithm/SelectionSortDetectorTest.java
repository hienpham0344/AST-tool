package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SortPattern.Direction;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelectionSortDetectorTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private final AlgorithmPatternAnalyzer analyzer = new AlgorithmPatternAnalyzer();

  private List<AlgorithmHint> detect(String loop) {
    return analyzer.analyze(
        parser.parseCompilationUnit(
            "class Sample { void solve(int[] data, int[] values) {\n" + loop + "\n} }"));
  }

  private String selection(String operator) {
    return """
        for (int pass = 0; pass < data.length - 1; pass++) {
          int min = pass;
          for (int scan = pass + 1; scan < data.length; scan++) {
            if (data[scan] %s data[min]) min = scan;
          }
          int temp = data[pass];
          data[pass] = data[min];
          data[min] = temp;
        }
        """
        .formatted(operator);
  }

  @Test
  void detectsAscendingSelectionAndBindsAllRuntimeRoles() {
    assertThat(detect(selection("<")))
        .singleElement()
        .satisfies(
            hint -> {
              assertThat(hint.type()).isEqualTo("selection-sort");
              assertThat(hint.visualPlan()).isEqualTo("array-sort");
              assertThat(hint.sort().direction()).isEqualTo(Direction.ASCENDING);
              assertThat(hint.sort().first().indexDeclarationId())
                  .isEqualTo(hint.variableDeclarationIds().get("scan"));
              assertThat(hint.sort().second().indexDeclarationId())
                  .isEqualTo(hint.variableDeclarationIds().get("selected"));
              assertThat(hint.sort().swapStatementAstNodeIds()).hasSize(3).doesNotContainNull();
              assertThat(hint.sort().operationAstNodeIds()).containsOnlyKeys("selectedIndexUpdate");
              assertThat(hint.variables())
                  .containsEntry("array", "data")
                  .containsEntry("pass", "pass")
                  .containsEntry("scan", "scan")
                  .containsEntry("selected", "min")
                  .containsEntry("temp", "temp");
              assertThat(hint.variableDeclarationIds().keySet())
                  .containsExactlyInAnyOrder("array", "pass", "scan", "selected", "temp");
            });
  }

  @Test
  void detectsDescendingSelectionAndRenamedVariables() {
    String source =
        selection(">")
            .replace("pass", "boundary")
            .replace("scan", "cursor")
            .replace("min", "chosen")
            .replace("temp", "saved")
            .replace("data", "values");
    assertThat(detect(source))
        .singleElement()
        .satisfies(
            hint -> {
              assertThat(hint.sort().direction()).isEqualTo(Direction.DESCENDING);
              assertThat(hint.variables())
                  .containsEntry("array", "values")
                  .containsEntry("pass", "boundary")
                  .containsEntry("scan", "cursor")
                  .containsEntry("selected", "chosen")
                  .containsEntry("temp", "saved");
            });
  }

  @Test
  void rejectsNearMissesAndIncompletePasses() {
    assertThat(detect(selection("<").replace("scan < data.length", "scan < data.length - 1")))
        .isEmpty();
    assertThat(detect(selection("<").replace("data[min] = temp;", "data[min] = data[pass];")))
        .isEmpty();
    assertThat(detect(selection("<="))).isEmpty();
    assertThat(detect(selection("<").replace("int min = pass;", "int min = 0;"))).isEmpty();
    assertThat(
            detect(
                selection("<")
                    .replace("int temp = data[pass];", "int temp = data[pass]; data[0] = 0;")))
        .isEmpty();
  }

  @Test
  void rejectsNonIntArrayInput() {
    assertThat(
            analyzer.analyze(
                parser.parseCompilationUnit(
                    "class Sample { void solve(long[] data, int[] values) {\n"
                        + selection("<")
                        + "\n} }")))
        .isEmpty();
  }
}
