package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.algorithm.sort.InsertionSortDetector;
import com.example.astchunker.algorithm.sort.SortPatternRegistry;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

class InsertionSortDetectorTest {
  private java.util.List<com.example.astchunker.model.AlgorithmHint> detect(String body) {
    var analysis =
        new AstAnalyzer()
            .analyze("public class Sample { void sort(int[] values) {\n" + body + "\n} }");
    return new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
  }

  private java.util.Optional<com.example.astchunker.model.AlgorithmHint> detectSemantic(String source) {
    var unit = new AstAnalyzer().analyze(source).compilationUnit();
    var session = SortPatternRegistry.defaults().begin(unit);
    var outer = unit.findAll(ForStmt.class).get(0);
    return new InsertionSortDetector().detect(outer, session);
  }

  @Test
  void recognizesAliasedShiftWithHarmlessScalarAndUnitCompoundDecrement() {
    var hint =
        detectSemantic(
            """
            public class Sample {
              void sort(int[] values) {
                int[] work = values;
                for (int pass = 1; pass < work.length; pass++) {
                  int key = work[pass];
                  int scan = pass - 1;
                  while (scan >= 0 && (work[scan] > key)) {
                    work[scan + 1] = work[scan];
                    int observed = scan;
                    scan -= 1;
                  }
                  work[scan + 1] = key;
                }
              }
            }
            """);

    assertThat(hint).isPresent();
    assertThat(hint.orElseThrow().type()).isEqualTo("insertion-sort");
    assertThat(hint.orElseThrow().confidence()).isEqualTo(0.96);
    assertThat(hint.orElseThrow().variables())
        .containsEntry("array", "work")
        .containsEntry("pass", "pass")
        .containsEntry("scan", "scan")
        .containsEntry("key", "key");
    assertThat(hint.orElseThrow().sort()).isNotNull();
  }

  @Test
  void recognizesResolvedComparisonHelperAsStaticOnly() {
    var hint =
        detectSemantic(
            """
            public class Sample {
              private static boolean greater(int left, int right) { return left > right; }
              void sort(int[] values) {
                for (int pass = 1; pass < values.length; pass++) {
                  int key = values[pass]; int scan = pass - 1;
                  while (scan >= 0 && greater(values[scan], key)) {
                    values[scan + 1] = values[scan]; --scan;
                  }
                  values[scan + 1] = key;
                }
              }
            }
            """);

    assertThat(hint).isPresent();
    assertThat(hint.orElseThrow().sort()).isNull();
    assertThat(hint.orElseThrow().confidence()).isEqualTo(0.96);
  }

  @Test
  void recognizesDescendingComparisonHelperWithKeyOnTheLeftAsStaticOnly() {
    var hint =
        detectSemantic(
            """
            public class Sample {
              private static boolean comesBefore(int left, int right) { return left > right; }
              void sort(int[] values) {
                for (int pass = 1; pass < values.length; pass++) {
                  int key = values[pass]; int scan = pass - 1;
                  while (scan >= 0 && comesBefore(key, values[scan])) {
                    values[scan + 1] = values[scan]; scan--;
                  }
                  values[scan + 1] = key;
                }
              }
            }
            """);

    assertThat(hint).isPresent();
    assertThat(hint.orElseThrow().sort()).isNull();
  }

  @Test
  void rejectsShiftThatDecrementsBeforeWritingTheMovedValue() {
    var hint =
        detectSemantic(
            """
            public class Sample { void sort(int[] values) {
              for (int pass = 1; pass < values.length; pass++) {
                int key = values[pass]; int scan = pass - 1;
                while (scan >= 0 && values[scan] > key) {
                  scan--; values[scan + 1] = values[scan];
                }
                values[scan + 1] = key;
              }
            } }
            """);

    assertThat(hint).isEmpty();
  }

  @Test
  void rejectsHarmlessScalarOutsideTheShiftAndDecrementWindow() {
    var hint =
        detectSemantic(
            """
            public class Sample { void sort(int[] values) {
              for (int pass = 1; pass < values.length; pass++) {
                int key = values[pass]; int scan = pass - 1;
                while (scan >= 0 && values[scan] > key) {
                  int observed = scan; values[scan + 1] = values[scan]; scan--;
                }
                values[scan + 1] = key;
              }
            } }
            """);

    assertThat(hint).isEmpty();
  }

  @Test
  void rejectsShiftWhenBoundaryFollowsArrayAccess() {
    var hint =
        detectSemantic(
            """
            public class Sample { void sort(int[] values) {
              for (int pass = 1; pass < values.length; pass++) {
                int key = values[pass]; int scan = pass - 1;
                while (values[scan] > key && scan >= 0) {
                  values[scan + 1] = values[scan]; scan--;
                }
                values[scan + 1] = key;
              }
            } }
            """);

    assertThat(hint).isEmpty();
  }

  @Test
  void recognizesBackwardAdjacentExchangeAsStaticOnly() {
    var hint =
        detectSemantic(
            """
            public class Sample { void sort(int[] values) {
              for (int pass = 1; pass < values.length; pass++) {
                for (int scan = pass; scan > 0 && values[scan] < values[scan - 1]; scan--) {
                  int temporary = values[scan];
                  values[scan] = values[scan - 1];
                  values[scan - 1] = temporary;
                }
              }
            } }
            """);

    assertThat(hint).isPresent();
    assertThat(hint.orElseThrow().type()).isEqualTo("insertion-sort");
    assertThat(hint.orElseThrow().sort()).isNull();
    assertThat(hint.orElseThrow().confidence()).isEqualTo(0.96);
  }

  @Test
  void recognizesReversedDirectBackwardAdjacentExchangeAsStaticOnly() {
    var hint =
        detectSemantic(
            """
            public class Sample { void sort(int[] values) {
              for (int pass = 1; pass < values.length; pass++) {
                for (int scan = pass; scan > 0 && values[scan] < values[scan - 1]; scan--) {
                  int temporary = values[scan - 1];
                  values[scan - 1] = values[scan];
                  values[scan] = temporary;
                }
              }
            } }
            """);

    assertThat(hint).isPresent();
    assertThat(hint.orElseThrow().sort()).isNull();
  }

  @Test
  void rejectsNonAdjacentBackwardExchange() {
    var hint =
        detectSemantic(
            """
            public class Sample { void sort(int[] values) {
              for (int pass = 1; pass < values.length; pass++) {
                for (int scan = pass; scan > 0 && values[scan] < values[scan - 1]; scan--) {
                  int temporary = values[scan];
                  values[scan] = values[0];
                  values[0] = temporary;
                }
              }
            } }
            """);

    assertThat(hint).isEmpty();
  }

  @Test
  void recognizesResolvedAdjacentExchangeAsStaticOnly() {
    String source =
            """
            public class Sample {
              private static void exchange(int[] array, int left, int right) {
                int temporary = array[left]; array[left] = array[right]; array[right] = temporary;
              }
              void sort(int[] values) {
                for (int pass = 1; pass < values.length; pass++) {
                  for (int scan = pass; scan > 0 && values[scan] < values[scan - 1]; scan--) {
                    exchange(values, scan, scan - 1);
                  }
                }
              }
            }
            """;
    var unit = new AstAnalyzer().analyze(source).compilationUnit();
    var hint = new InsertionSortDetector().detect(unit.findFirst(ForStmt.class).orElseThrow(), SortPatternRegistry.defaults().begin(unit));
    String helperId = AstAnalyzer.nodeId(unit.findFirst(MethodDeclaration.class, method -> method.getNameAsString().equals("exchange")).orElseThrow());
    String callId = AstAnalyzer.nodeId(unit.findFirst(MethodCallExpr.class).orElseThrow());

    assertThat(hint).isPresent();
    assertThat(hint.orElseThrow().sort()).isNull();
    assertThat(hint.orElseThrow().evidence()).anySatisfy(value -> assertThat(value).contains(helperId).contains(callId));
  }

  @Test
  void recordsComparisonHelperAndCallSiteIdsInEvidence() {
    String source =
        """
        public class Sample {
          private static boolean greater(int left, int right) { return left > right; }
          void sort(int[] values) {
            for (int pass = 1; pass < values.length; pass++) {
              int key = values[pass]; int scan = pass - 1;
              while (scan >= 0 && greater(values[scan], key)) { values[scan + 1] = values[scan]; scan--; }
              values[scan + 1] = key;
            }
          }
        }
        """;
    var unit = new AstAnalyzer().analyze(source).compilationUnit();
    var hint = new InsertionSortDetector().detect(unit.findFirst(ForStmt.class).orElseThrow(), SortPatternRegistry.defaults().begin(unit));
    String helperId = AstAnalyzer.nodeId(unit.findFirst(MethodDeclaration.class, method -> method.getNameAsString().equals("greater")).orElseThrow());
    String callId = AstAnalyzer.nodeId(unit.findFirst(MethodCallExpr.class).orElseThrow());

    assertThat(hint).isPresent();
    assertThat(hint.orElseThrow().evidence()).anySatisfy(value -> assertThat(value).contains(helperId).contains(callId));
  }

  @Test
  void rejectsForwardScanMissingFinalPlacementAndUnsafeOuterForms() {
    var forward = detectSemantic(
        """
        public class Sample { void sort(int[] values) { for (int pass = 1; pass < values.length; pass++) {
          int key = values[pass]; int scan = pass - 1; while (scan >= 0 && values[scan] > key) { values[scan + 1] = values[scan]; scan++; } values[scan + 1] = key;
        } } }
        """);
    var missingPlacement = detectSemantic(
        """
        public class Sample { void sort(int[] values) { for (int pass = 1; pass < values.length; pass++) {
          int key = values[pass]; int scan = pass - 1; while (scan >= 0 && values[scan] > key) { values[scan + 1] = values[scan]; scan--; }
        } } }
        """);
    var unsafeBound = detectSemantic(
        """
        public class Sample { void sort(int[] values) { for (int pass = 1; pass <= values.length; pass++) {
          int key = values[pass]; int scan = pass - 1; while (scan >= 0 && values[scan] > key) { values[scan + 1] = values[scan]; scan--; } values[scan + 1] = key;
        } } }
        """);

    assertThat(forward).isEmpty();
    assertThat(missingPlacement).isEmpty();
    assertThat(unsafeBound).isEmpty();
  }

  @Test
  void rejectsReboundLocalsInterferingMutationPartialPrefixAndOpaqueHelper() {
    var reboundKey = detectSemantic(
        """
        public class Sample { void sort(int[] values) { for (int pass = 1; pass < values.length; pass++) {
          int key = values[pass]; key = 0; int scan = pass - 1;
          while (scan >= 0 && values[scan] > key) { values[scan + 1] = values[scan]; scan--; } values[scan + 1] = key;
        } } }
        """);
    var reboundScan = detectSemantic(
        """
        public class Sample { void sort(int[] values) { for (int pass = 1; pass < values.length; pass++) {
          int key = values[pass]; int scan = pass - 1; scan = 0;
          while (scan >= 0 && values[scan] > key) { values[scan + 1] = values[scan]; scan--; } values[scan + 1] = key;
        } } }
        """);
    var mutation = detectSemantic(
        """
        public class Sample { void sort(int[] values) { for (int pass = 1; pass < values.length; pass++) {
          int key = values[pass]; int scan = pass - 1;
          while (scan >= 0 && values[scan] > key) { values[scan + 1] = values[scan]; scan--; }
          values[0] = 7; values[scan + 1] = key;
        } } }
        """);
    var partialPrefix = detectSemantic(
        """
        public class Sample { void sort(int[] values) { for (int pass = 2; pass < values.length; pass++) {
          int key = values[pass]; int scan = pass - 1;
          while (scan >= 0 && values[scan] > key) { values[scan + 1] = values[scan]; scan--; } values[scan + 1] = key;
        } } }
        """);
    var opaqueHelper = detectSemantic(
        """
        public class Sample { boolean greater(int left, int right) { return left > right; }
          void sort(int[] values) { for (int pass = 1; pass < values.length; pass++) {
            int key = values[pass]; int scan = pass - 1;
            while (scan >= 0 && greater(values[scan], key)) { values[scan + 1] = values[scan]; scan--; } values[scan + 1] = key;
          } }
        }
        """);

    assertThat(reboundKey).isEmpty();
    assertThat(reboundScan).isEmpty();
    assertThat(mutation).isEmpty();
    assertThat(partialPrefix).isEmpty();
    assertThat(opaqueHelper).isEmpty();
  }

  @Test
  void semanticCanonicalShiftMetadataMatchesTheLegacyDetector() {
    String body =
        """
        for (int pass = 1; pass < values.length; pass++) {
          int key = values[pass]; int scan = pass - 1;
          while (scan >= 0 && values[scan] > key) { values[scan + 1] = values[scan]; scan--; }
          values[scan + 1] = key;
        }
        """;
    var legacy = detect(body).get(0);
    var semantic = detectSemantic("public class Sample { void sort(int[] values) {\n" + body + "\n} }").orElseThrow();

    assertThat(semantic.variableDeclarationIds()).isEqualTo(legacy.variableDeclarationIds());
    assertThat(semantic.sort()).isEqualTo(legacy.sort());
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
