package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.sort.HeapSortDetector;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SortPattern.Direction;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HeapSortDetectorTest {
  private static final String SOURCE =
      """
      class Example {
        private static void sort(int[] data) {
          int size = data.length;
          for (int start = size / 2 - 1; start >= 0; start--) {
            descend(start, data, size);
          }
          for (int end = size - 1; end > 0; end--) {
            int saved = data[0];
            data[0] = data[end];
            data[end] = saved;
            descend(0, data, end);
          }
        }
        private static void descend(int cursor, int[] values, int limit) {
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
  private static final String INLINE_SIFT =
      """
      while (2 * cursor + 1 < limit) {
        int left = 2 * cursor + 1;
        int right = left + 1;
        int chosen = left;
        if (right < limit && data[right] > data[left]) chosen = right;
        if (data[cursor] >= data[chosen]) break;
        int temp = data[cursor];
        data[cursor] = data[chosen];
        data[chosen] = temp;
        cursor = chosen;
      }
      """;

  @Test
  void provesBottomUpHeapWithReorderedHelperParametersAndRenamedRoles() {
    AlgorithmHint hint = detect(SOURCE).orElseThrow();
    assertThat(hint.type()).isEqualTo("heap-sort");
    assertThat(hint.heap()).isNotNull();
    assertThat(hint.heap().direction()).isEqualTo(Direction.ASCENDING);
    assertThat(hint.heap().buildKind()).isEqualTo("BOTTOM_UP");
    assertThat(hint.heap().operationAstNodeIds())
        .containsKeys(
            "buildLoop",
            "extractLoop",
            "rootSwapSave",
            "rootSwapRootWrite",
            "rootSwapEndWrite",
            "restoreCall");
    assertThat(hint.variableDeclarationIds()).containsKeys("array", "size", "buildCursor", "end");
    assertThat(
            detect(
                    SOURCE
                        .replace("values[right] > values[left]", "values[right] < values[left]")
                        .replace(
                            "values[cursor] >= values[chosen]", "values[cursor] <= values[chosen]"))
                .orElseThrow()
                .heap()
                .direction())
        .isEqualTo(Direction.DESCENDING);
  }

  @Test
  void rejectsNearMissesThatBreakHeapOrExtractionProof() {
    for (String changed :
        List.of(
            SOURCE.replace("int start = size / 2 - 1", "int start = size / 2 - 2"),
            SOURCE.replace("descend(start, data, size);", ""),
            SOURCE.replace("data[0] = data[end];", "data[0] = 0;"),
            SOURCE.replace("data[end] = saved;", "data[end - 1] = saved;"),
            SOURCE.replace("descend(0, data, end);", "descend(0, data, size);"),
            SOURCE.replace("descend(0, data, end);", "descend(0, new int[size], end);"),
            SOURCE.replace("end--", "end++"),
            SOURCE.replace("data[end] = saved;", "data[end] = saved; data[end + 1] = 1;"))) {
      assertThat(detect(changed)).as(changed).isEmpty();
    }
  }

  @Test
  void acceptsWhileBuildAndExtractionWithEquivalentCounterUpdates() {
    String changed =
        SOURCE
            .replace(
                "for (int start = size / 2 - 1; start >= 0; start--) {",
                "int start = size / 2 - 1; while (start >= 0) {")
            .replace("descend(start, data, size);", "descend(start, data, size); start -= 1;")
            .replace(
                "for (int end = size - 1; end > 0; end--) {",
                "int end = size - 1; while (end > 0) {")
            .replace("descend(0, data, end);", "descend(0, data, end); end -= 1;");
    assertThat(detect(changed)).isPresent();
  }

  @Test
  void acceptsTopDownBuildOnlyWhenEveryInsertedElementIsSifted() {
    String source =
        SOURCE
            .replace(
                "for (int start = size / 2 - 1; start >= 0; start--) {",
                "for (int start = 1; start < size; start++) {")
            .replace("descend(start, data, size);", "rise(start, data, size);")
            .replace(
                "private static void descend(int cursor, int[] values, int limit) {",
                "private static void rise(int cursor, int[] values, int limit) {\n"
                    + "while (cursor > 0) {\n"
                    + "int parent = (cursor - 1) / 2;\n"
                    + "if (values[parent] >= values[cursor]) break;\n"
                    + "int saved = values[parent];\n"
                    + "values[parent] = values[cursor];\n"
                    + "values[cursor] = saved;\n"
                    + "cursor = parent;\n"
                    + "}\n}\nprivate static void descend(int cursor, int[] values, int limit) {");
    assertThat(detect(source).orElseThrow().heap().buildKind()).isEqualTo("TOP_DOWN");
    String whileBuild =
        source
            .replace(
                "for (int start = 1; start < size; start++) {",
                "int start = 1; while (start < size) {")
            .replace("rise(start, data, size);", "rise(start, data, size); start += 1;");
    assertThat(detect(whileBuild).orElseThrow().heap().buildKind()).isEqualTo("TOP_DOWN");
    assertThat(detect(source.replace("int start = 1", "int start = 2"))).isEmpty();
    assertThat(detect(source.replace("start++", "start--"))).isEmpty();
    assertThat(
            detect(
                source.replace(
                    "rise(start, data, size);", "data = new int[size]; rise(start, data, size);")))
        .isEmpty();
  }

  @Test
  void normalizesInclusiveLastIndexWithoutOffByOne() {
    String source =
        SOURCE
            .replace("descend(start, data, size);", "descend(start, data, size - 1);")
            .replace("descend(0, data, end);", "descend(0, data, end - 1);")
            .replace("2 * cursor + 1 < limit", "2 * cursor + 1 <= limit")
            .replace("right < limit", "right <= limit");
    assertThat(detect(source).orElseThrow().heap().siftInclusiveBoundary()).isTrue();
    assertThat(detect(source).orElseThrow().heap().buildSiftInclusiveBoundary()).isTrue();
    assertThat(detect(source.replace("right <= limit", "right < limit"))).isEmpty();
  }

  @Test
  void acceptsEquivalentSiftBodyInlinedIntoBothPhases() {
    String source =
        SOURCE
            .replace(
                "descend(start, data, size);",
                "int cursor = start; " + INLINE_SIFT.replace("limit", "size"))
            .replace(
                "descend(0, data, end);", "int cursor = 0; " + INLINE_SIFT.replace("limit", "end"));
    assertThat(detect(source)).isPresent();
    assertThat(detect(source.replace("data[chosen] = temp;", "data[chosen] = 0;"))).isEmpty();
  }

  @Test
  void acceptsImmutableLocalArrayAliasButRejectsReassignment() {
    String alias =
        SOURCE
            .replace(
                "int size = data.length;", "int[] work = data;\n          int size = work.length;")
            .replace("data[", "work[")
            .replace("descend(start, data, size)", "descend(start, work, size)")
            .replace("descend(0, data, end)", "descend(0, work, end)");
    assertThat(detect(alias)).isPresent();
    assertThat(
            detect(
                alias.replace("int size = work.length;", "work = data; int size = work.length;")))
        .isEmpty();
  }

  @Test
  void acceptsCombinedAliasWhileLoopsAndReversedComparisons() {
    String combined =
        SOURCE
            .replace("int size = data.length;", "int[] work = data; int size = work.length;")
            .replace("data[", "work[")
            .replace("descend(start, data, size)", "descend(start, work, size)")
            .replace("descend(0, data, end)", "descend(0, work, end)")
            .replace(
                "for (int start = size / 2 - 1; start >= 0; start--) {",
                "int start = size / 2 - 1; while (start >= 0) {")
            .replace("descend(start, work, size);", "descend(start, work, size); start -= 1;")
            .replace(
                "for (int end = size - 1; end > 0; end--) {",
                "int end = size - 1; while (end > 0) {")
            .replace("descend(0, work, end);", "descend(0, work, end); end -= 1;")
            .replace("values[right] > values[left]", "values[left] < values[right]")
            .replace("values[cursor] >= values[chosen]", "values[chosen] <= values[cursor]");
    assertThat(detect(combined)).isPresent();
  }

  private static Optional<AlgorithmHint> detect(String source) {
    var unit = new AstAnalyzer().parseCompilationUnit(source);
    var sort = unit.getClassByName("Example").orElseThrow().getMethodsByName("sort").get(0);
    var body = sort.getBody().orElseThrow().getStatements();
    return new HeapSortDetector().detect(body.get(body.size() - 1));
  }
}
