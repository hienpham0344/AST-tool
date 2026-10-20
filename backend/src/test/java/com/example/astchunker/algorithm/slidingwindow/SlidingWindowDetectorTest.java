package com.example.astchunker.algorithm.slidingwindow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlidingWindowDetectorTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private final AlgorithmPatternAnalyzer analyzer = new AlgorithmPatternAnalyzer();

  private List<AlgorithmHint> detect(String source) {
    return analyzer.analyze(parser.parseCompilationUnit(source));
  }

  @Test
  void recognizesGuardedFixedArrayWithExpandedAssignmentsAndRenamedRoles() {
    var hints = detect("""
        class Sample { void run(int[] numbers, int width) {
          if (width <= 0 || width > numbers.length) return;
          int rolling = 0;
          int cursor = 0;
          while (cursor < numbers.length) {
            rolling = rolling + numbers[cursor];
            if (cursor >= width) rolling = rolling - numbers[cursor - width];
            cursor++;
          }
        } }
        """);
    assertThat(hints).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("sliding-window");
      assertThat(hint.slidingWindow().kind()).isEqualTo("FIXED_SIZE");
      assertThat(hint.slidingWindow().representation()).isEqualTo("INT_ARRAY");
      assertThat(hint.variableDeclarationIds()).containsKeys("data", "right", "accumulator", "windowSize");
    });
  }

  @Test
  void recognizesVariableListWithCompoundAssignments() {
    var hints = detect("""
        class Sample { void run(java.util.List<Integer> values, int limit) {
          int start = 0, rolling = 0;
          for (int cursor = 0; cursor < values.size(); cursor++) {
            rolling += values.get(cursor);
            while (rolling >= limit && start <= cursor) {
              rolling -= values.get(start);
              start++;
            }
          }
        } }
        """);
    assertThat(hints).extracting(h -> h.type()).containsExactly("sliding-window");
    assertThat(hints.get(0).slidingWindow().kind()).isEqualTo("VARIABLE_SIZE");
    assertThat(hints.get(0).slidingWindow().representation()).isEqualTo("INTEGER_LIST");
  }

  @Test
  void recognizesFixedListAndSpanGuardedVariableArray() {
    var fixed = detect("""
        class Sample { void run(java.util.List<Integer> values, int width) {
          if (width <= 0) return;
          int sum = 0;
          for (int scan = 0; scan < values.size(); scan++) {
            sum += values.get(scan);
            if (scan >= width) sum -= values.get(scan-width);
          }
        } }
        """);
    assertThat(fixed).singleElement().satisfies(hint -> {
      assertThat(hint.slidingWindow().kind()).isEqualTo("FIXED_SIZE");
      assertThat(hint.slidingWindow().representation()).isEqualTo("INTEGER_LIST");
    });
    var variable = detect("""
        class Sample { void run(int[] values, int width) {
          if (width <= 0) return;
          int left = 0, sum = 0;
          for (int scan = 0; scan < values.length; scan++) {
            sum += values[scan];
            if (scan - left + 1 > width) {
              sum -= values[left];
              left++;
            }
          }
        } }
        """);
    assertThat(variable).singleElement().satisfies(hint ->
        assertThat(hint.slidingWindow().kind()).isEqualTo("VARIABLE_SIZE"));
  }

  @Test
  void rejectsSkippedAdditionAndDifferentInput() {
    assertThat(detect("""
        class Sample { void run(int[] a, boolean choose, int k) {
          if (k <= 0 || k > a.length) return;
          int sum = 0;
          for (int i = 0; i < a.length; i++) {
            if (choose) sum += a[i];
            if (i >= k) sum -= a[i-k];
          }
        } }
        """)).isEmpty();
    assertThat(detect("""
        class Sample { void run(int[] a, int[] b, int k) {
          if (k <= 0 || k > a.length) return;
          int sum = 0;
          for (int i = 0; i < a.length; i++) {
            sum += a[i];
            if (i >= k) sum -= b[i-k];
          }
        } }
        """)).isEmpty();
  }

  @Test
  void rejectsUnprotectedOutgoingIndexAndReboundAccumulator() {
    assertThat(detect("""
        class Sample { void run(int[] a, int k) {
          int sum = 0;
          for (int i = 0; i < a.length; i++) {
            sum += a[i];
            sum -= a[i-k];
          }
        } }
        """)).isEmpty();
    assertThat(detect("""
        class Sample { void run(int[] a, int k) {
          if (k <= 0 || k > a.length) return;
          int sum = 0;
          for (int i = 0; i < a.length; i++) {
            sum += a[i];
            sum = 7;
            if (i >= k) sum -= a[i-k];
          }
        } }
        """)).isEmpty();
  }

  @Test
  void rejectsMutatedSizeAndPreLoopPointerOrAccumulator() {
    String body = """
        class Sample { void run(int[] a, int k) {
          if (k <= 0) return;
          int sum = 0;
          for (int i = 0; i < a.length; i++) {
            sum += a[i];
            if (i >= k) sum -= a[i-k];
          }
        } }
        """;
    assertThat(detect(body.replace("sum += a[i];", "k++; sum += a[i];"))).isEmpty();
    assertThat(detect(body.replace("int sum = 0;", "int sum = 0; sum = 99;"))).isEmpty();
    assertThat(detect(body.replace("for (int i = 0;", "int i = 0; i = -1; for (;")))
        .isEmpty();
  }

  @Test
  void rejectsPositiveSizeGuardThatMayBeSkipped() {
    assertThat(detect("""
        class Sample { void run(int[] a, int k, boolean flag) {
          if (flag) { if (k <= 0) return; }
          int sum = 0;
          for (int i = 0; i < a.length; i++) {
            sum += a[i];
            if (i >= k) sum -= a[i-k];
          }
        } }
        """)).isEmpty();
  }

  @Test
  void rejectsPreLoopLeftMutationAndConditionallySkippedRemoval() {
    assertThat(detect("""
        class Sample { void run(int[] a, int limit) {
          int left = 0, sum = 0;
          left = -1;
          for (int right = 0; right < a.length; right++) {
            sum += a[right];
            while (sum > limit && left <= right) {
              sum -= a[left];
              left++;
            }
          }
        } }
        """)).isEmpty();
    assertThat(detect("""
        class Sample { void run(int[] a, int width, boolean enabled) {
          if (width <= 0) return;
          int sum = 0;
          for (int right = 0; right < a.length; right++) {
            sum += a[right];
            if (enabled) {
              if (right >= width) sum -= a[right - width];
            }
          }
        } }
        """)).isEmpty();
  }

  @Test
  void rejectsExtraLeftUpdateWithinOuterLoop() {
    assertThat(detect("""
        class Sample { void run(int[] a, int limit) {
          int left = 0, sum = 0;
          for (int right = 0; right < a.length; right++) {
            sum += a[right];
            left++;
            while (sum > limit && left <= right) {
              sum -= a[left];
              left++;
            }
          }
        } }
        """)).isEmpty();
  }

  @Test
  void rejectsWritesToTheInputArray() {
    assertThat(detect("""
        class Sample { void run(int[] a, int width) {
          if (width <= 0) return;
          int sum = 0;
          for (int right = 0; right < a.length; right++) {
            sum += a[right];
            a[right] = 0;
            if (right >= width) sum -= a[right - width];
          }
        } }
        """)).isEmpty();
  }
}
