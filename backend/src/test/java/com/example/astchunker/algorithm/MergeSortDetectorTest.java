package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SortPattern.Direction;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class MergeSortDetectorTest {
  private static final String SOURCE =
      """
      class Sample {
        private static void merge(int[] data, int low, int high) {
          if (low >= high) return;
          int mid = low + (high - low) / 2;
          merge(data, low, mid);
          merge(data, mid + 1, high);
          int[] aux = new int[high - low + 1];
          int left = low;
          int right = mid + 1;
          int write = 0;
          while (left <= mid && right <= high) {
            if (data[left] <= data[right]) {
              aux[write] = data[left];
              left++;
            } else {
              aux[write] = data[right];
              right++;
            }
            write++;
          }
          while (left <= mid) {
            aux[write] = data[left];
            left++;
            write++;
          }
          while (right <= high) {
            aux[write] = data[right];
            right++;
            write++;
          }
          for (int index = low; index <= high; index++) {
            data[index] = aux[index - low];
          }
        }
      }
      """;

  private List<AlgorithmHint> hints(String source) {
    return new AlgorithmPatternAnalyzer().analyze(new AstAnalyzer().parseCompilationUnit(source));
  }

  @Test
  void recognizesCompleteTopDownMergeWithOperationIds() {
    assertThat(hints(SOURCE))
        .singleElement()
        .satisfies(
            hint -> {
              assertThat(hint.type()).isEqualTo("merge-sort");
              assertThat(hint.merge().direction()).isEqualTo(Direction.ASCENDING);
              assertThat(hint.merge().operationAstNodeIds())
                  .containsKeys(
                      "guard",
                      "split",
                      "leftCall",
                      "rightCall",
                      "comparison",
                      "leftWrite",
                      "rightWrite",
                      "leftRemainderWrite",
                      "rightRemainderWrite",
                      "copyBackWrite");
              assertThat(hint.variableDeclarationIds())
                  .containsKeys(
                      "array", "low", "high", "mid", "aux", "left", "right", "write", "index");
            });
  }

  @Test
  void acceptsNamesDirectionAndEquivalentCopyBackLoop() {
    assertThat(
            hints(
                SOURCE
                    .replace("data", "items")
                    .replace("low", "start")
                    .replace("high", "end")
                    .replace("left", "lhs")
                    .replace("right", "rhs")
                    .replace("write", "out")))
        .singleElement()
        .satisfies(hint -> assertThat(hint.type()).isEqualTo("merge-sort"));
    assertThat(hints(SOURCE.replace("data[left] <= data[right]", "data[left] >= data[right]")))
        .singleElement()
        .satisfies(hint -> assertThat(hint.merge().direction()).isEqualTo(Direction.DESCENDING));
    String whileCopy =
        SOURCE.replace(
            "for (int index = low; index <= high; index++) {\n      data[index] = aux[index - low];\n    }",
            "int index = low;\n    while (index <= high) {\n"
                + "      data[index] = aux[index - low];\n      index++;\n    }");
    assertThat(whileCopy).contains("while (index <= high)");
    assertThat(hints(whileCopy))
        .singleElement()
        .satisfies(hint -> assertThat(hint.type()).isEqualTo("merge-sort"));
    String instanceMethod =
        SOURCE
            .replace("private static void merge", "private void merge")
            .replace("merge(data, low, mid);", "this.merge(data, low, mid);")
            .replace("merge(data, mid + 1, high);", "this.merge(data, mid + 1, high);");
    assertThat(hints(instanceMethod))
        .singleElement()
        .satisfies(hint -> assertThat(hint.type()).isEqualTo("merge-sort"));
  }

  @Test
  void recognizesGuardedBodyAndClassQualifiedRecursion() {
    assertThat(hints(guardedBody(SOURCE)))
        .singleElement()
        .satisfies(hint -> assertThat(hint.type()).isEqualTo("merge-sort"));
    assertThat(hints(SOURCE.replace("merge(data,", "Sample.merge(data,")))
        .singleElement()
        .satisfies(hint -> assertThat(hint.type()).isEqualTo("merge-sort"));
  }

  @Test
  void recognizesReversedComparisonOperandsWithoutChangingDirection() {
    assertThat(hints(SOURCE.replace("data[left] <= data[right]", "data[right] >= data[left]")))
        .singleElement()
        .satisfies(hint -> assertThat(hint.merge().direction()).isEqualTo(Direction.ASCENDING));
    assertThat(hints(SOURCE.replace("data[left] <= data[right]", "data[right] <= data[left]")))
        .singleElement()
        .satisfies(hint -> assertThat(hint.merge().direction()).isEqualTo(Direction.DESCENDING));
    assertThat(hints(SOURCE.replace("data[left] <= data[right]", "data[right] == data[left]")))
        .noneMatch(hint -> hint.type().equals("merge-sort"));
    assertThat(hints(SOURCE.replace("data[left] <= data[right]", "data[right + 1] >= data[left]")))
        .noneMatch(hint -> hint.type().equals("merge-sort"));
  }

  @Test
  void rejectsUnsafeGuardAndForeignRecursiveCall() {
    List<String> negatives =
        List.of(
            guardedBody(SOURCE).replace("low < high", "low <= high"),
            guardedBody(SOURCE).replace("\n    }\n  }\n}", "\n    }\n    data[0] = 0;\n  }\n}"),
            SOURCE
                .replace(
                    "class Sample {",
                    "class Other { static void merge(int[] data, int low, int high) {} }\n"
                        + "class Sample { static Other Sample = new Other();")
                .replace("merge(data,", "Sample.merge(data,"),
            SOURCE
                .replace(
                    "class Sample {",
                    "class Other { static void merge(int[] data, int low, int high) {} }\n"
                        + "interface I { Other Sample = new Other(); }\n"
                        + "class Sample implements I {")
                .replace("merge(data,", "Sample.merge(data,"),
            SOURCE.replace("merge(data, low, mid);", "Other.merge(data, low, mid);"));
    for (String source : negatives)
      assertThat(hints(source)).noneMatch(hint -> hint.type().equals("merge-sort"));
  }

  private static String guardedBody(String source) {
    String rewritten = source.replace("if (low >= high) return;", "if (low < high) {");
    int methodEnd = rewritten.lastIndexOf("\n  }\n}");
    assertThat(methodEnd).isPositive();
    return rewritten.substring(0, methodEnd + 1) + "    }" + rewritten.substring(methodEnd);
  }

  @Test
  void rejectsIncompleteOrWrongMergeEffects() {
    List<String> negatives =
        List.of(
            SOURCE.replace("merge(data, low, mid);", "merge(data, low, mid - 1);"),
            SOURCE.replace("merge(data, mid + 1, high);", "merge(data, mid, high);"),
            SOURCE.replace(
                "int[] aux = new int[high - low + 1];", "int[] aux = new int[high - low];"),
            SOURCE.replace("aux[write] = data[right];", "aux[write] = data[left];"),
            SOURCE.replace("data[index] = aux[index - low];", "data[index] = aux[index];"),
            SOURCE.replace("data[index] = aux[index - low];", "data[index] = 0;"),
            SOURCE.replace("right++;\n      write++;", "write++;"),
            SOURCE.replace("while (right <= high)", "while (right < high)"),
            SOURCE.replace(
                "for (int index = low; index <= high; index++)",
                "aux[0] = 999;\n    for (int index = low; index <= high; index++)"));
    for (String source : negatives)
      assertThat(hints(source)).noneMatch(hint -> hint.type().equals("merge-sort"));
  }

  @Test
  void serializesMergeMetadataAdditively() {
    var hint = hints(SOURCE).get(0);
    var json = new ObjectMapper().valueToTree(hint);
    assertThat(json.path("merge").path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(json.path("merge").path("operationAstNodeIds").path("copyBackWrite").asText())
        .startsWith("ast-");
    assertThat(json.has("sort")).isFalse();
    assertThat(json.has("quick")).isFalse();
  }
}
