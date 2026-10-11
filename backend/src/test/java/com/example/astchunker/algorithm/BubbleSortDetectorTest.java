package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class BubbleSortDetectorTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private final AlgorithmPatternAnalyzer analyzer = new AlgorithmPatternAnalyzer();

  private List<AlgorithmHint> detect(String loop) {
    return analyzer.analyze(
        parser.parseCompilationUnit("class Sample { void solve(int[] data) {\n" + loop + "\n} }"));
  }

  private String bubble(String condition, String rightIndex) {
    return """
        for (int pass = 0; pass < data.length - 1; pass++) {
          for (int scan = 0; scan < data.length - 1 - pass; scan++) {
            if (%s) {
              int temp = data[scan];
              data[scan] = data[%s];
              data[%s] = temp;
            }
          }
        }
        """
        .formatted(condition, rightIndex, rightIndex);
  }

  @Test
  void detectsAscendingBubbleAndEmitsVersionedAstMetadata() throws Exception {
    var hints = detect(bubble("data[scan] > data[scan + 1]", "scan + 1"));
    assertThat(hints)
        .singleElement()
        .satisfies(
            hint -> {
              assertThat(hint.type()).isEqualTo("bubble-sort");
              assertThat(hint.confidence()).isEqualTo(0.95);
              assertThat(hint.variables())
                  .containsEntry("array", "data")
                  .containsEntry("pass", "pass")
                  .containsEntry("scan", "scan")
                  .containsEntry("temp", "temp");
              assertThat(hint.variableDeclarationIds().keySet())
                  .containsExactlyInAnyOrder("array", "pass", "scan", "temp");
              assertThat(hint.sort().schemaVersion()).isEqualTo(1);
              assertThat(hint.sort().direction())
                  .isEqualTo(com.example.astchunker.model.SortPattern.Direction.ASCENDING);
              assertThat(hint.sort().swapStatementAstNodeIds()).hasSize(3).doesNotContainNull();
              assertThat(hint.sort().first().indexDeclarationId())
                  .isEqualTo(hint.variableDeclarationIds().get("scan"));
              assertThat(hint.sort().second().offset()).isEqualTo(1);
              assertThat(hint.sort().innerLoopAstNodeId()).isNotBlank();
              assertThat(hint.sort().comparisonAstNodeId()).isNotBlank();
              assertThat(hint.sort().conditionStatementAstNodeId()).isNotBlank();
            });
    String json = new ObjectMapper().writeValueAsString(hints.get(0));
    assertThat(json)
        .contains(
            "\"sort\":{",
            "\"schemaVersion\":1",
            "\"direction\":\"ASCENDING\"",
            "\"offset\":1",
            "\"swapStatementAstNodeIds\":[");
  }

  @Test
  void detectsDescendingAndCommonCounterSyntax() {
    String source =
        bubble("data[scan] < data[scan + 1]", "scan + 1")
            .replace("pass++)", "pass = pass + 1)")
            .replace("scan++)", "++scan)");
    assertThat(detect(source))
        .singleElement()
        .satisfies(
            hint ->
                assertThat(hint.sort().direction())
                    .isEqualTo(com.example.astchunker.model.SortPattern.Direction.DESCENDING));
  }

  @Test
  void discoversSemanticRolesWhenTheUserChoosesDifferentVariableNames() {
    String source =
        bubble("data[scan] > data[scan + 1]", "scan + 1")
            .replace("pass", "round")
            .replace("scan", "cursor")
            .replace("temp", "saved");
    assertThat(detect(source))
        .singleElement()
        .satisfies(
            hint ->
                assertThat(hint.variables())
                    .containsEntry("pass", "round")
                    .containsEntry("scan", "cursor")
                    .containsEntry("temp", "saved"));
  }

  @Test
  void rejectsNearMissesAndIncompleteEvidence() {
    assertThat(detect(bubble("data[scan] > data[scan + 2]", "scan + 2"))).isEmpty();
    assertThat(
            detect(
                bubble("data[scan] > data[scan + 1]", "scan + 1")
                    .replace("data[scan + 1] = temp;", "data[scan + 1] = data[scan];")))
        .isEmpty();
    assertThat(
            detect(
                bubble("data[scan] > data[scan + 1]", "scan + 1")
                    .replace(
                        "data[scan + 1] = temp;",
                        "data[scan + 1] = temp;\n              data[0] = 0;")))
        .isEmpty();
    assertThat(detect(bubble("data[scan] >= data[scan + 1]", "scan + 1"))).isEmpty();
    assertThat(detect(bubble("data[scan] > other[scan + 1]", "scan + 1"))).isEmpty();
    assertThat(
            detect(
                """
        for (int pass = 0; pass < data.length - 1; pass++) {
          for (int scan = 0; scan < data.length - 1 - pass; scan++) {
            if (data[scan] > data[scan + 1]) { data[scan] = data[scan + 1]; }
          }
        }
        """))
        .isEmpty();
  }

  @Test
  void rejectsWrongArrayTypeAndExtraStatementsInsideSwapBlock() {
    assertThat(
            analyzer.analyze(
                parser.parseCompilationUnit(
                    """
        class Sample { void solve(long[] data) {
          for (int pass = 0; pass < data.length - 1; pass++)
            for (int scan = 0; scan < data.length - 1 - pass; scan++)
              if (data[scan] > data[scan + 1]) {
                long temp = data[scan]; data[scan] = data[scan + 1]; data[scan + 1] = temp;
              }
        } }
        """)))
        .isEmpty();

    String shadowed =
        bubble("data[scan] > data[scan + 1]", "scan + 1")
            .replace(
                "int temp = data[scan];",
                "int temp = data[scan]; { int scan = 0; data[scan] = data[scan]; }");
    assertThat(detect(shadowed)).isEmpty();
  }
}
