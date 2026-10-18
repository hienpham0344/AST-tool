package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.SortPattern.Direction;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuickSortDetectorTest {
  private static final String ASCENDING = """
      class Sample {
        private static void sort(int[] data, int low, int high) {
          if (low >= high) return;
          int pivot = data[high];
          int store = low;
          for (int scan = low; scan < high; scan++) {
            if (data[scan] <= pivot) {
              int saved = data[store];
              data[store] = data[scan];
              data[scan] = saved;
              store++;
            }
          }
          int finalSaved = data[store];
          data[store] = data[high];
          data[high] = finalSaved;
          sort(data, low, store - 1);
          sort(data, store + 1, high);
        }
      }
      """;

  private List<com.example.astchunker.model.AlgorithmHint> hints(String source) {
    return new AlgorithmPatternAnalyzer().analyze(new AstAnalyzer().parseCompilationUnit(source));
  }

  @Test
  void recognizesCompleteAscendingLomutoFlowWithOperationIds() {
    assertThat(hints(ASCENDING))
        .singleElement()
        .satisfies(hint -> {
          assertThat(hint.type()).isEqualTo("quick-sort");
          assertThat(hint.quick()).isNotNull();
          assertThat(hint.quick().direction()).isEqualTo(Direction.ASCENDING);
          assertThat(hint.quick().operationAstNodeIds())
              .containsKeys("guard", "pivotRead", "scanLoop", "comparison",
                  "innerSwapSave", "innerSwapLeftWrite", "innerSwapRightWrite",
                  "finalSwapSave", "finalSwapLeftWrite", "finalSwapRightWrite",
                  "leftCall", "rightCall");
        });
  }

  @Test
  void serializesQuickMetadataWithoutChangingLegacySortPayload() throws Exception {
    var quick = hints(ASCENDING).get(0);
    var json = new ObjectMapper().valueToTree(quick);
    assertThat(json.path("quick").path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(json.path("quick").path("operationAstNodeIds").path("finalSwapRightWrite").asText())
        .startsWith("ast-");
    assertThat(json.has("sort")).isFalse();
    var legacy = new AlgorithmPatternAnalyzer().analyze(new AstAnalyzer().parseCompilationUnit("""
        class Sample {
          void sort(int[] data) {
            for (int pass = 0; pass < data.length - 1; pass++) {
              for (int scan = 0; scan < data.length - 1 - pass; scan++) {
                if (data[scan] > data[scan + 1]) {
                  int saved = data[scan];
                  data[scan] = data[scan + 1];
                  data[scan + 1] = saved;
                }
              }
            }
          }
        }
        """)).get(0);
    var legacyJson = new ObjectMapper().valueToTree(legacy);
    assertThat(legacyJson.has("quick")).isFalse();
    assertThat(legacyJson.path("sort").path("schemaVersion").asInt()).isEqualTo(1);
  }

  @Test
  void recognizesDescendingAndEquivalentWhileScan() {
    String descending = ASCENDING.replace("data[scan] <= pivot", "data[scan] >= pivot");
    assertThat(hints(descending)).singleElement().satisfies(hint ->
        assertThat(hint.quick().direction()).isEqualTo(Direction.DESCENDING));
    String whileScan = """
        class Sample {
          private static void sort(int[] data, int low, int high) {
            if (low >= high) return;
            int pivot = data[high];
            int store = low;
            int scan = low;
            while (scan < high) {
              if (data[scan] <= pivot) {
                int saved = data[store];
                data[store] = data[scan];
                data[scan] = saved;
                store++;
              }
              scan++;
            }
            int finalSaved = data[store];
            data[store] = data[high];
            data[high] = finalSaved;
            sort(data, low, store - 1);
            sort(data, store + 1, high);
          }
        }
        """;
    assertThat(hints(whileScan)).singleElement().satisfies(hint ->
        assertThat(hint.type()).isEqualTo("quick-sort"));
  }

  @Test
  void rejectsWrongRangePivotSwapOrRecursion() {
    List<String> negatives = List.of(
        ASCENDING.replace("scan < high", "scan < high - 1"),
        ASCENDING.replace("int pivot = data[high]", "int pivot = data[low]"),
        ASCENDING.replace("data[high] = finalSaved", "data[low] = finalSaved"),
        ASCENDING.replace("sort(data, low, store - 1)", "sort(data, low, store)"),
        ASCENDING.replace("sort(data, store + 1, high)", "sort(data, store + 2, high)"),
        ASCENDING.replace("data[scan] = saved", "data[scan] = 0"));
    for (String source : negatives)
      assertThat(hints(source)).noneMatch(hint -> hint.type().equals("quick-sort"));
  }
}
