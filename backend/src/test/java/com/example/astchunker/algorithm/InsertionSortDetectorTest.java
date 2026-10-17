package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.algorithm.sort.InsertionSortDetector;
import com.example.astchunker.algorithm.sort.SortPatternRegistry;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
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

  @Test
  void recognizesEachNonIntNumericPrimitiveShiftAsStaticOnly() {
    for (String type : List.of("byte", "short", "long", "char", "float", "double")) {
      String source = shiftSource(type);
      var unit = new AstAnalyzer().analyze(source).compilationUnit();
      var result = SortPatternRegistry.defaults().begin(unit)
          .evaluate(unit.findFirst(ForStmt.class).orElseThrow());

      assertThat(result.status()).as(type).isEqualTo(SortPatternRegistry.Status.UNIQUE);
      assertThat(result.candidates()).as(type).hasSize(1);
      var hint = result.candidates().get(0);
      assertThat(hint.type()).isEqualTo("insertion-sort");
      assertThat(hint.sort()).isNull();
      assertThat(hint.variableDeclarationIds())
          .containsEntry("array", AstAnalyzer.nodeId(unit.findFirst(Parameter.class).orElseThrow()));
      for (String role : List.of("pass", "scan", "key")) {
        String declarationId = AstAnalyzer.nodeId(unit.findFirst(
            VariableDeclarator.class, variable -> variable.getNameAsString().equals(role)).orElseThrow());
        assertThat(hint.variableDeclarationIds()).containsEntry(role, declarationId);
      }
    }
  }

  @Test
  void recognizesExactLongComparisonAndAdjacentExchangeHelpersAsStaticOnly() {
    String comparisonSource =
        """
        public class Sample {
          private static boolean greater(long left, long right) { return left > right; }
          void sort(long[] values) {
            for (int pass = 1; pass < values.length; pass++) {
              long key = values[pass]; int scan = pass - 1;
              while (scan >= 0 && greater(values[scan], key)) { values[scan + 1] = values[scan]; scan--; }
              values[scan + 1] = key;
            }
          }
        }
        """;
    String exchangeSource =
        """
        public class Sample {
          private static void exchange(long[] array, int left, int right) {
            long temporary = array[left]; array[left] = array[right]; array[right] = temporary;
          }
          void sort(long[] values) {
            for (int pass = 1; pass < values.length; pass++) {
              for (int scan = pass; scan > 0 && values[scan] < values[scan - 1]; scan--) {
                exchange(values, scan, scan - 1);
              }
            }
          }
        }
        """;
    var comparison = detectSemantic(comparisonSource);
    var exchange = detectSemantic(exchangeSource);
    var comparisonUnit = new AstAnalyzer().analyze(comparisonSource).compilationUnit();
    var exchangeUnit = new AstAnalyzer().analyze(exchangeSource).compilationUnit();
    String comparisonHelperId = AstAnalyzer.nodeId(comparisonUnit.findFirst(
        MethodDeclaration.class, method -> method.getNameAsString().equals("greater")).orElseThrow());
    String comparisonCallId = AstAnalyzer.nodeId(comparisonUnit.findFirst(MethodCallExpr.class).orElseThrow());
    String exchangeHelperId = AstAnalyzer.nodeId(exchangeUnit.findFirst(
        MethodDeclaration.class, method -> method.getNameAsString().equals("exchange")).orElseThrow());
    String exchangeCallId = AstAnalyzer.nodeId(exchangeUnit.findFirst(MethodCallExpr.class).orElseThrow());

    assertThat(comparison).isPresent();
    assertThat(comparison.orElseThrow().sort()).isNull();
    assertThat(comparison.orElseThrow().evidence())
        .anySatisfy(value -> assertThat(value).contains(comparisonHelperId).contains(comparisonCallId));
    assertThat(exchange).isPresent();
    assertThat(exchange.orElseThrow().sort()).isNull();
    assertThat(exchange.orElseThrow().evidence())
        .anySatisfy(value -> assertThat(value).contains(exchangeHelperId).contains(exchangeCallId));
  }

  @Test
  void ignoresUnusedKeyArgumentInLongComparisonHelper() {
    var hint = detectSemantic(
        """
        public class Sample {
          private static boolean greater(long left, long alternate, long unusedKey) {
            return left > alternate;
          }
          void sort(long[] values, long alternate) {
            for (int pass = 1; pass < values.length; pass++) {
              long key = values[pass]; int scan = pass - 1;
              while (scan >= 0 && greater(values[scan], alternate, key)) {
                values[scan + 1] = values[scan]; scan--;
              }
              values[scan + 1] = key;
            }
          }
        }
        """);
    assertThat(hint).isEmpty();
  }

  @Test
  void rejectsNonNumericAndMismatchedPrimitiveInsertionForms() {
    var booleanArray = detectSemantic(shiftSource("boolean"));
    var objectArray = detectSemantic(
        """
        public class Sample { void sort(Integer[] values) {
          for (int pass = 1; pass < values.length; pass++) {
            Integer key = values[pass]; int scan = pass - 1;
            while (scan >= 0 && values[scan] > key) { values[scan + 1] = values[scan]; scan--; }
            values[scan + 1] = key;
          }
        } }
        """);
    var narrowedKey = detectSemantic(
        """
        public class Sample { void sort(long[] values) {
          for (int pass = 1; pass < values.length; pass++) {
            int key = (int) values[pass]; int scan = pass - 1;
            while (scan >= 0 && values[scan] > key) { values[scan + 1] = values[scan]; scan--; }
            values[scan + 1] = key;
          }
        } }
        """);
    var mismatchedTemporary = detectSemantic(
        """
        public class Sample { void sort(long[] values) {
          for (int pass = 1; pass < values.length; pass++) {
            for (int scan = pass; scan > 0 && values[scan] < values[scan - 1]; scan--) {
              int temporary = (int) values[scan];
              values[scan] = values[scan - 1]; values[scan - 1] = temporary;
            }
          }
        } }
        """);

    assertThat(booleanArray).isEmpty();
    assertThat(objectArray).isEmpty();
    assertThat(narrowedKey).isEmpty();
    assertThat(mismatchedTemporary).isEmpty();
  }

  @Test
  void recognizesComparatorGuardedReferenceInsertionStaticallyWithExactBindings() {
    String source =
        """
        public class Sample {
          void sort(String[] data, java.util.Comparator<String> order) {
            for (int pass = 1; pass < data.length; pass++) {
              String key = data[pass]; int scan = pass - 1;
              while (scan >= 0 && order.compare(data[scan], key) > 0) {
                data[scan + 1] = data[scan]; scan--;
              }
              data[scan + 1] = key;
            }
          }
        }
        """;
    var unit = new AstAnalyzer().analyze(source).compilationUnit();
    var result = SortPatternRegistry.defaults().begin(unit)
        .evaluate(unit.findFirst(ForStmt.class).orElseThrow());

    assertThat(result.status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(result.candidates()).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("insertion-sort");
      assertThat(hint.sort()).isNull();
      assertThat(hint.variableDeclarationIds())
          .containsEntry("array", AstAnalyzer.nodeId(unit.findAll(Parameter.class).get(0)))
          .containsEntry("comparator", AstAnalyzer.nodeId(unit.findAll(Parameter.class).get(1)));
      for (String role : List.of("pass", "scan", "key")) {
        String id = AstAnalyzer.nodeId(unit.findAll(VariableDeclarator.class).stream()
            .filter(variable -> variable.getNameAsString().equals(role)).findFirst().orElseThrow());
        assertThat(hint.variableDeclarationIds()).containsEntry(role, id);
      }
    });

    String reversed = source.replace("order.compare(data[scan], key) > 0",
        "order.compare(key, data[scan]) < 0");
    var reversedUnit = new AstAnalyzer().analyze(reversed).compilationUnit();
    assertThat(SortPatternRegistry.defaults().begin(reversedUnit)
        .evaluate(reversedUnit.findFirst(ForStmt.class).orElseThrow()))
        .satisfies(resultForReversed -> {
          assertThat(resultForReversed.status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
          assertThat(resultForReversed.candidates()).singleElement()
              .satisfies(hint -> assertThat(hint.sort()).isNull());
        });
  }

  @Test
  void rejectsComparatorInsertionNearMisses() {
    String valid =
        """
        public class Sample {
          void sort(String[] data, String[] other, java.util.Comparator<String> order) {
            for (int pass = 1; pass < data.length; pass++) {
              String key = data[pass]; int scan = pass - 1;
              while (scan >= 0 && order.compare(data[scan], key) > 0) {
                data[scan + 1] = data[scan]; scan--;
              }
              data[scan + 1] = key;
            }
          }
        }
        """;
    for (String invalid : List.of(
        valid.replace("java.util.Comparator<String> order", "java.util.Comparator<Object> order"),
        valid.replace("data[scan], key", "other[scan], key"),
        valid.replace("data[scan], key", "data[scan], data[pass]"),
        valid.replace("> 0", "> 1"),
        valid.replace("String key", "Object key").replace("key = data[pass]", "key = data[pass]"))) {
      var unit = new AstAnalyzer().analyze(invalid).compilationUnit();
      assertThat(SortPatternRegistry.defaults().begin(unit)
          .evaluate(unit.findFirst(ForStmt.class).orElseThrow()).status())
          .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    }
  }

  @Test
  void recognizesFullyQualifiedNaturalOrderInsertionAsStaticOnlyWithExactBindings() {
    String source =
        """
        public class Sample {
          void sort(java.lang.String[] values) {
            for (int pass = 1; pass < values.length; pass++) {
              java.lang.String key = values[pass]; int scan = pass - 1;
              while (scan >= 0 && values[scan].compareTo(key) > 0) {
                values[scan + 1] = values[scan]; scan--;
              }
              values[scan + 1] = key;
            }
          }
        }
        """;
    var unit = new AstAnalyzer().analyze(source).compilationUnit();
    var result = SortPatternRegistry.defaults().begin(unit)
        .evaluate(unit.findFirst(ForStmt.class).orElseThrow());

    assertThat(result.status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(result.candidates()).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("insertion-sort");
      assertThat(hint.sort()).isNull();
      assertThat(hint.variableDeclarationIds())
          .containsEntry("array", AstAnalyzer.nodeId(unit.findFirst(Parameter.class).orElseThrow()));
      for (String role : List.of("pass", "scan", "key")) {
        String id = AstAnalyzer.nodeId(unit.findFirst(
            VariableDeclarator.class, variable -> variable.getNameAsString().equals(role)).orElseThrow());
        assertThat(hint.variableDeclarationIds()).containsEntry(role, id);
      }
    });

    String zeroLeftReversed = source.replace("values[scan].compareTo(key) > 0", "0 < key.compareTo(values[scan])");
    var reversedUnit = new AstAnalyzer().analyze(zeroLeftReversed).compilationUnit();
    assertThat(SortPatternRegistry.defaults().begin(reversedUnit)
        .evaluate(reversedUnit.findFirst(ForStmt.class).orElseThrow()).status())
        .isEqualTo(SortPatternRegistry.Status.UNIQUE);
  }

  @Test
  void rejectsNaturalOrderInsertionNearMisses() {
    String valid =
        """
        public class Sample {
          void sort(java.lang.String[] values, java.lang.String[] other) {
            for (int pass = 1; pass < values.length; pass++) {
              java.lang.String key = values[pass]; int scan = pass - 1;
              while (scan >= 0 && values[scan].compareTo(key) > 0) {
                values[scan + 1] = values[scan]; scan--;
              }
              values[scan + 1] = key;
            }
          }
        }
        """;
    for (String invalid : List.of(
        valid.replace("java.lang.String[] values", "String[] values"),
        valid.replace("values[scan].compareTo(key)", "other[scan].compareTo(key)"),
        valid.replace("java.lang.String key", "Object key").replace("key = values[pass]", "key = values[pass]"),
        valid.replace("values[scan].compareTo(key)", "((java.lang.String) values[scan]).compareTo(key)"),
        valid.replace("values[scan].compareTo(key)", "values[scan].trim().compareTo(key)"),
        valid.replace("> 0", "> 1"),
        valid.replace("> 0", ">= 0"))) {
      var unit = new AstAnalyzer().analyze(invalid).compilationUnit();
      assertThat(SortPatternRegistry.defaults().begin(unit)
          .evaluate(unit.findFirst(ForStmt.class).orElseThrow()).status())
          .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    }
  }

  private static String shiftSource(String type) {
    return """
        public class Sample { void sort(%s[] values) {
          for (int pass = 1; pass < values.length; pass++) {
            %s key = values[pass]; int scan = pass - 1;
            while (scan >= 0 && values[scan] > key) { values[scan + 1] = values[scan]; scan--; }
            values[scan + 1] = key;
          }
        } }
        """.formatted(type, type);
  }
}
