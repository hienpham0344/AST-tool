package com.example.astchunker.algorithm.greedy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import org.junit.jupiter.api.Test;

class ActivitySelectionDetectorTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private final AlgorithmPatternAnalyzer analyzer = new AlgorithmPatternAnalyzer();

  @Test
  void recognizesMatrixActivitySelectionWithRenamedVariables() {
    var unit = parser.parseCompilationUnit("""
        class Sample {
          int select(int[][] windows) {
            int boundary = Integer.MIN_VALUE;
            int total = 0;
            for (int cursor = 0; cursor < windows.length; cursor++) {
              int[] current = windows[cursor];
              if (current[0] >= boundary) {
                total++;
                boundary = current[1];
              }
            }
            return total;
          }
        }
        """);
    assertThat(analyzer.analyze(unit)).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("activity-selection");
      assertThat(hint.variables()).containsEntry("input", "windows");
      assertThat(hint.activitySelection().overlapPolicy().name()).isEqualTo("HALF_OPEN");
      assertThat(hint.activitySelection().orderingEvidence().name()).isEqualTo("INPUT_PRECONDITION");
      assertThat(hint.activitySelection().roleValues()).containsEntry("start", "0").containsEntry("end", "1");
    });
  }

  @Test
  void recognizesRecordActivitySelectionUsingWhileAndClosedBoundary() {
    var unit = parser.parseCompilationUnit("""
        import java.util.List;
        class Sample {
          record Interval(int begin, int finish) {}
          int select(List<Interval> values) {
            int end = Integer.MIN_VALUE, count = 0, i = 0;
            while (i < values.size()) {
              Interval value = values.get(i);
              if (count == 0 || end < value.begin()) {
                count += 1;
                end = value.finish();
              }
              i++;
            }
            return count;
          }
        }
        """);
    assertThat(analyzer.analyze(unit)).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("activity-selection");
      assertThat(hint.variables()).containsEntry("input", "values");
      assertThat(hint.activitySelection().overlapPolicy().name()).isEqualTo("CLOSED");
    });
  }

  @Test
  void recognizesEnhancedForAndListOutput() {
    var unit = parser.parseCompilationUnit("""
        import java.util.ArrayList;
        import java.util.List;
        class Sample {
          record Window(int start, int end) {}
          List<Window> select(List<Window> input) {
            List<Window> chosen = new ArrayList<>();
            int last = Integer.MIN_VALUE;
            for (Window candidate : input) {
              if (candidate.start() >= last) {
                chosen.add(candidate);
                last = candidate.end();
              }
            }
            return chosen;
          }
        }
        """);
    assertThat(analyzer.analyze(unit)).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("activity-selection");
      assertThat(hint.activitySelection().roleValues()).containsEntry("start", "start").containsEntry("end", "end");
      assertThat(hint.activitySelection().roleValues())
          .containsEntry("runtimeType", "Sample$Window");
    });
  }

  @Test
  void recognizesEnhancedForOverMatrixRows() {
    var unit = parser.parseCompilationUnit("""
        class Sample {
          int select(int[][] intervals) {
            int last = Integer.MIN_VALUE, count = 0;
            for (int[] row : intervals) {
              if (row[1] >= last) { count++; last = row[0]; }
            }
            return count;
          }
        }
        """);
    assertThat(analyzer.analyze(unit)).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("activity-selection");
      assertThat(hint.activitySelection().roleValues()).containsEntry("start", "1")
          .containsEntry("end", "0");
    });
  }

  @Test
  void recognizesImmutableEndpointAliasesButRejectsReassignedOnes() {
    String source = """
        class Sample {
          int select(int[][] data) {
            int last = Integer.MIN_VALUE, count = 0;
            for (int i = 0; i < data.length; i++) {
              int[] slot = data[i];
              int begin = slot[0];
              int finish = slot[1];
              if (begin >= last) { count++; last = finish; }
            }
            return count;
          }
        }
        """;
    assertThat(analyzer.analyze(parser.parseCompilationUnit(source)))
        .singleElement().satisfies(hint ->
            assertThat(hint.type()).isEqualTo("activity-selection"));
    assertThat(analyzer.analyze(parser.parseCompilationUnit(
        source.replace("if (begin >= last)", "begin++; if (begin >= last)")))).isEmpty();
  }

  @Test
  void recognizesPurePrivateEndpointHelpers() {
    String source = """
        class Sample {
          private static int beginning(int[] interval) { return interval[0]; }
          private static int ending(int[] interval) { return interval[1]; }
          int select(int[][] data) {
            int last = Integer.MIN_VALUE, count = 0;
            for (int i = 0; i < data.length; i++) {
              int[] slot = data[i];
              if (beginning(slot) >= last) { count++; last = ending(slot); }
            }
            return count;
          }
        }
        """;
    var unit = parser.parseCompilationUnit(source);
    assertThat(IntervalRoles.resolve(unit,
        unit.findFirst(com.github.javaparser.ast.stmt.ForStmt.class).orElseThrow())).isPresent();
    assertThat(analyzer.analyze(unit)).singleElement()
        .satisfies(hint -> assertThat(hint.type()).isEqualTo("activity-selection"));
    assertThat(analyzer.analyze(parser.parseCompilationUnit(source.replace(
        "private static int beginning", "int beginning")))).isEmpty();
    assertThat(analyzer.analyze(parser.parseCompilationUnit(source.replace(
        "return interval[0];", "System.out.println(interval[0]); return interval[0];"))))
        .isEmpty();
  }

  @Test
  void rejectsWrongStateUpdateAndMissingCandidate() {
    var wrongUpdate = parser.parseCompilationUnit("""
        class Sample {
          int select(int[][] data) {
            int last = Integer.MIN_VALUE, count = 0;
            for (int i = 0; i < data.length; i++) {
              int[] x = data[i];
              if (x[0] >= last) { count++; last = x[0]; }
            }
            return count;
          }
        }
        """);
    var skipped = parser.parseCompilationUnit("""
        class Sample {
          int select(int[][] data) {
            int last = Integer.MIN_VALUE, count = 0;
            for (int i = 1; i < data.length; i++) {
              int[] x = data[i];
              if (x[0] >= last) { count++; last = x[1]; }
            }
            return count;
          }
        }
        """);
    assertThat(analyzer.analyze(wrongUpdate)).isEmpty();
    assertThat(analyzer.analyze(skipped)).isEmpty();
  }

  @Test
  void rejectsPartialScanUnsafeBoundaryAndUnrelatedGuard() {
    String source = """
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
        """;
    for (String variant : new String[] {
        source.replace("i < data.length", "i <= data.length"),
        source.replace("int last = Integer.MIN_VALUE", "int last = 0"),
        source.replace("data[i];", "data[0];"),
        source.replace("x[0] >= last", "x[0] >= last || count == 7"),
        source.replace("count++; last", "data[i][0] = 10; count++; last"),
        source.replace("if (x[0] >= last) { count++; last = x[1]; }",
            "if (x[0] >= last) { count++; last = x[1]; } last++;"),
        source.replace("if (x[0] >= last) { count++; last = x[1]; }",
            "if (x[0] >= last) { count++; last = x[1]; } count++;")
    }) {
      assertThat(analyzer.analyze(parser.parseCompilationUnit(variant)))
          .as(variant).isEmpty();
    }
  }

  @Test
  void rejectsHelperThatCanMutateInputDuringScan() {
    var unit = parser.parseCompilationUnit("""
        class Sample {
          void tamper(int[][] data) { data[0][1] = 0; }
          int select(int[][] data) {
            int last = Integer.MIN_VALUE, count = 0;
            for (int i = 0; i < data.length; i++) {
              int[] x = data[i];
              tamper(data);
              if (x[0] >= last) { count++; last = x[1]; }
            }
            return count;
          }
        }
        """);
    assertThat(analyzer.analyze(unit)).isEmpty();
  }

  @Test
  void rejectsStateChangedBetweenInitializationAndScan() {
    String source = """
        class Sample {
          int select(int[][] data) {
            int last = Integer.MIN_VALUE, count = 0;
            last = 10;
            for (int i = 0; i < data.length; i++) {
              int[] x = data[i];
              if (x[0] >= last) { count++; last = x[1]; }
            }
            return count;
          }
        }
        """;
    assertThat(analyzer.analyze(parser.parseCompilationUnit(source))).isEmpty();
    assertThat(analyzer.analyze(parser.parseCompilationUnit(
        source.replace("last = 10;", "count = 3;")))).isEmpty();
    assertThat(analyzer.analyze(parser.parseCompilationUnit(
        source.replace("last = 10;", "")
            .replace("return count;", "count++; return count;")))).isEmpty();
  }
}
