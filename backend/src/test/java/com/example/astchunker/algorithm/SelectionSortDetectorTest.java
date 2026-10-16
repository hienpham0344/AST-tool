package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.algorithm.sort.SortPatternRegistry;
import com.example.astchunker.algorithm.sort.SelectionSortDetector;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SortPattern.Direction;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SelectionSortDetectorTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private final AlgorithmPatternAnalyzer analyzer = new AlgorithmPatternAnalyzer();

  private List<AlgorithmHint> detect(String loop) {
    return analyzer.analyze(
        parser.parseCompilationUnit(
            "class Sample { void solve(int[] data, int[] values) {\n" + loop + "\n} }"));
  }

  private List<AlgorithmHint> detectSemantic(String members) {
    CompilationUnit unit =
        parser.parseCompilationUnit("class Sample { " + members + " }");
    ForStmt outer = unit.findFirst(ForStmt.class).orElseThrow();
    return SortPatternRegistry.defaults().begin(unit).evaluate(outer).candidates();
  }

  private SortPatternRegistry.Evaluation evaluateSemantic(String members) {
    CompilationUnit unit = parser.parseCompilationUnit("class Sample { " + members + " }");
    ForStmt outer = unit.findFirst(ForStmt.class).orElseThrow();
    return SortPatternRegistry.defaults().begin(unit).evaluate(outer);
  }

  private SortPatternRegistry.Evaluation evaluateTypedSemantic(
      String type, String body, String helpers) {
    CompilationUnit unit = parser.parseCompilationUnit(
        "class Sample { void solve(" + type + "[] data) { " + body + " } " + helpers + " }");
    return SortPatternRegistry.defaults().begin(unit)
        .evaluate(unit.findFirst(ForStmt.class).orElseThrow());
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

  @ParameterizedTest
  @ValueSource(strings = {"byte", "short", "long", "char", "float", "double"})
  void recognizesNumericPrimitiveSelectionStaticallyWithoutSortFrames(String type) {
    String body = selection("<").replace("int temp", type + " temp");
    CompilationUnit unit = parser.parseCompilationUnit(
        "class Sample { void solve(" + type + "[] data) { " + body + " } }");
    var result = SortPatternRegistry.defaults().begin(unit)
        .evaluate(unit.findFirst(ForStmt.class).orElseThrow());
    assertThat(result.status())
        .isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(result.candidates())
        .singleElement()
        .satisfies(hint -> {
          assertThat(hint.type()).isEqualTo("selection-sort");
          assertThat(hint.sort()).isNull();
          assertThat(hint.variableDeclarationIds())
              .containsEntry("array", AstAnalyzer.nodeId(unit.findFirst(Parameter.class).orElseThrow()));
          for (var role : Map.of("pass", "pass", "scan", "scan", "selected", "min", "temp", "temp").entrySet()) {
            String id = unit.findAll(VariableDeclarator.class).stream()
                .filter(variable -> variable.getNameAsString().equals(role.getValue()))
                .findFirst().map(AstAnalyzer::nodeId).orElseThrow();
            assertThat(hint.variableDeclarationIds()).containsEntry(role.getKey(), id);
          }
        });
  }

  @Test
  void recognizesExactLongSelectionExchangeHelperButRejectsMismatchedTypes() {
    String body = """
        for (int pass = 0; pass < data.length - 1; pass++) {
          int selected = pass;
          for (int scan = pass + 1; scan < data.length; scan++) {
            if (data[scan] < data[selected]) selected = scan;
          }
          exchange(data, pass, selected);
        }
        """;
    String exact =
        "private static void exchange(long[] values, int left, int right) { long saved = values[left]; values[left] = values[right]; values[right] = saved; }";
    CompilationUnit exactUnit = parser.parseCompilationUnit(
        "class Sample { void solve(long[] data) { " + body + " } " + exact + " }");
    String callId = AstAnalyzer.nodeId(exactUnit.findFirst(MethodCallExpr.class).orElseThrow());
    String helperId = AstAnalyzer.nodeId(exactUnit.findFirst(MethodDeclaration.class,
        method -> method.getNameAsString().equals("exchange")).orElseThrow());
    assertThat(evaluateTypedSemantic("long", body, exact).candidates())
        .singleElement()
        .satisfies(hint -> {
          assertThat(hint.type()).isEqualTo("selection-sort");
          assertThat(hint.sort()).isNull();
          assertThat(hint.evidence()).anyMatch(item -> item.contains(callId) && item.contains(helperId));
        });

    String wrongArray = exact.replace("long[]", "int[]").replace("long saved", "int saved");
    assertThat(evaluateTypedSemantic("long", body, wrongArray).status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    assertThat(evaluateTypedSemantic("long", body, exact.replace("long saved", "int saved")).status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
  }

  @Test
  void rejectsBooleanObjectNestedAndMismatchedSelectionTemporary() {
    String numericBody = selection("<");
    for (String type : List.of("boolean", "String", "int[]")) {
      String body = numericBody.replace("int temp", type + " temp");
      assertThat(evaluateTypedSemantic(type, body, "").status())
          .as(type)
          .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    }
    assertThat(evaluateTypedSemantic(
            "long", numericBody.replace("int temp", "int temp"), "").status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
  }

  @Test
  void recognizesGuardedSemanticSelectionWithAliasesAndReversedComparison() {
    String source =
        """
        void solve(int[] data) {
          int[] work = data;
          for (int boundary = 0; boundary < work.length - 1; boundary += 1) {
            int chosen = boundary;
            for (int cursor = boundary + 1; cursor < work.length; ++cursor) {
              int observed = cursor;
              if (work[chosen] > work[cursor]) chosen = cursor;
            }
            if (chosen != boundary) {
              int saved = work[boundary];
              work[boundary] = work[chosen];
              work[chosen] = saved;
            }
          }
        }
        """;

    CompilationUnit unit = parser.parseCompilationUnit("class Sample { " + source + " }");
    ForStmt outer = unit.findFirst(ForStmt.class).orElseThrow();
    AlgorithmHint hint = SortPatternRegistry.defaults().begin(unit).evaluate(outer).candidates().get(0);
    String chosenId =
        unit.findAll(VariableDeclarator.class).stream()
            .filter(variable -> variable.getNameAsString().equals("chosen"))
            .findFirst()
            .map(AstAnalyzer::nodeId)
            .orElseThrow();

    assertThat(hint.type()).isEqualTo("selection-sort");
    assertThat(hint.confidence()).isEqualTo(0.96);
    assertThat(hint.sort().direction()).isEqualTo(Direction.ASCENDING);
    assertThat(hint.variableDeclarationIds()).containsEntry("selected", chosenId);
    String updateStatementId =
        unit.findAll(ExpressionStmt.class).stream()
            .filter(statement -> statement.toString().contains("chosen = cursor"))
            .findFirst()
            .map(AstAnalyzer::nodeId)
            .orElseThrow();
    assertThat(hint.sort().operationAstNodeIds())
        .containsEntry("selectedIndexUpdate", updateStatementId);
    ForStmt inner = outer.getBody().asBlockStmt().getStatement(1).asForStmt();
    var branch = inner.getBody().asBlockStmt().getStatement(1).asIfStmt();
    var guard = outer.getBody().asBlockStmt().getStatement(2).asIfStmt();
    assertThat(hint.sort().innerLoopAstNodeId()).isEqualTo(AstAnalyzer.nodeId(inner));
    assertThat(hint.sort().comparisonAstNodeId())
        .isEqualTo(AstAnalyzer.nodeId(branch.getCondition()));
    assertThat(hint.sort().conditionStatementAstNodeId())
        .isEqualTo(AstAnalyzer.nodeId(branch));
    assertThat(hint.sort().swapStatementAstNodeIds())
        .containsExactlyElementsOf(
            guard.getThenStmt().asBlockStmt().getStatements().stream()
                .map(AstAnalyzer::nodeId).toList());
  }

  @Test
  void evaluatesCanonicalUnguardedSelectionAsAUniqueSemanticCandidate() {
    CompilationUnit unit = parser.parseCompilationUnit(
        "class Sample { void solve(int[] data) { " + selection("<") + " } }");
    ForStmt outer = unit.findFirst(ForStmt.class).orElseThrow();
    SortPatternRegistry.Evaluation result = SortPatternRegistry.defaults().begin(unit).evaluate(outer);
    AlgorithmHint legacy = new SelectionSortDetector().detect(outer).orElseThrow();

    assertThat(result.status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(result.candidates())
        .singleElement()
        .satisfies(
            hint -> {
              assertThat(hint.type()).isEqualTo("selection-sort");
              assertThat(hint.sort()).isNotNull();
              assertThat(hint.sort()).isEqualTo(legacy.sort());
              assertThat(hint.variableDeclarationIds()).isEqualTo(legacy.variableDeclarationIds());
              assertThat(hint.variableDeclarationIds())
                  .containsKeys("array", "pass", "scan", "selected", "temp");
              assertThat(hint.methodAstNodeId()).isNotBlank();
              assertThat(hint.patternAstNodeId()).isNotBlank();
            });
  }

  @Test
  void recognizesGuardedSelectionWhenTheDirectExchangeStartsAtSelectedIndex() {
    String source =
        """
        void solve(int[] data) {
          for (int pass = 0; pass < data.length - 1; pass++) {
            int selected = pass;
            for (int scan = pass + 1; scan < data.length; scan++) {
              if (data[scan] < data[selected]) selected = scan;
            }
            if (selected != pass) {
              int saved = data[selected];
              data[selected] = data[pass];
              data[pass] = saved;
            }
          }
        }
        """;

    assertThat(evaluateSemantic(source).candidates())
        .singleElement()
        .satisfies(
            hint -> {
              assertThat(hint.sort()).isNull();
              assertThat(hint.variables()).containsEntry("temp", "saved");
            });
  }

  @Test
  void recognizesResolvedSelectionExchangeAsStaticOnly() {
    String source =
        """
        private static void exchange(int[] values, int left, int right) {
          int saved = values[left];
          values[left] = values[right];
          values[right] = saved;
        }
        void solve(int[] items) {
          for (int pass = 0; pass < items.length - 1; pass++) {
            int best = pass;
            for (int scan = pass + 1; scan < items.length; scan++) {
              if (items[scan] > items[best]) best = scan;
            }
            if (best != pass) exchange(items, pass, best);
          }
        }
        """;

    CompilationUnit unit = parser.parseCompilationUnit("class Sample { " + source + " }");
    ForStmt outer = unit.findFirst(ForStmt.class).orElseThrow();
    MethodCallExpr call = unit.findFirst(MethodCallExpr.class).orElseThrow();
    String helperId =
        unit.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
            .filter(method -> method.getNameAsString().equals("exchange"))
            .findFirst()
            .map(AstAnalyzer::nodeId)
            .orElseThrow();

    assertThat(SortPatternRegistry.defaults().begin(unit).evaluate(outer).candidates())
        .singleElement()
        .satisfies(
            hint -> {
              assertThat(hint.type()).isEqualTo("selection-sort");
              assertThat(hint.confidence()).isEqualTo(0.96);
              assertThat(hint.sort()).isNull();
              assertThat(hint.variableDeclarationIds()).containsKeys("array", "pass", "scan", "selected");
              assertThat(hint.evidence()).anySatisfy(evidence -> {
                assertThat(evidence).contains(helperId).contains(AstAnalyzer.nodeId(call));
              });
            });
  }

  @Test
  void recognizesUnguardedResolvedSelectionExchangeAsStaticOnly() {
    String source =
        """
        private static void exchange(int[] values, int left, int right) {
          int saved = values[left];
          values[left] = values[right];
          values[right] = saved;
        }
        void solve(int[] data) {
          for (int pass = 0; pass < data.length - 1; pass++) {
            int selected = pass;
            for (int scan = pass + 1; scan < data.length; scan++) {
              if (data[scan] < data[selected]) selected = scan;
            }
            exchange(data, pass, selected);
          }
        }
        """;

    assertThat(evaluateSemantic(source).status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(detectSemantic(source)).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("selection-sort");
      assertThat(hint.sort()).isNull();
      assertThat(hint.variableDeclarationIds()).containsKeys("array", "pass", "scan", "selected");
    });
  }

  @Test
  void rejectsSemanticSelectionWhenTheSelectedIndexIsUpdatedOutsideItsComparisonGuard() {
    String source =
        """
        void solve(int[] data) {
          for (int pass = 0; pass < data.length - 1; pass++) {
            int selected = pass;
            for (int scan = pass + 1; scan < data.length; scan++) {
              if (data[scan] < data[selected]) { }
              selected = scan;
            }
            if (selected != pass) {
              int saved = data[pass];
              data[pass] = data[selected];
              data[selected] = saved;
            }
          }
        }
        """;

    assertThat(detectSemantic(source)).isEmpty();
  }

  @Test
  void rejectsSemanticSelectionWithUnprovenBoundaryOrPlacement() {
    String valid =
        """
        void solve(int[] data, int[] other) {
          for (int pass = 0; pass < data.length - 1; pass++) {
            int selected = pass;
            for (int scan = pass + 1; scan < data.length; scan++) {
              if (data[scan] < data[selected]) selected = scan;
            }
            if (selected != pass) {
              int saved = data[pass];
              data[pass] = data[selected];
              data[selected] = saved;
            }
          }
        }
        """;

    assertThat(detectSemantic(valid.replace("int selected = pass;", "int selected = 0;"))).isEmpty();
    assertThat(detectSemantic(valid.replace("data[selected]) selected", "other[selected]) selected"))).isEmpty();
    assertThat(detectSemantic(valid.replace("scan < data.length", "scan < data.length - 1"))).isEmpty();
    assertThat(detectSemantic(valid.replace("scan < data.length", "scan < data.length - (1 + 0)"))).isEmpty();
    assertThat(detectSemantic(valid.replace("data[pass] = data[selected];", "data[pass] = 0;"))).isEmpty();
    assertThat(
            detectSemantic(
                valid.replace(
                    "if (selected != pass) {", "data[0] = 0;\n            if (selected != pass) {")))
        .isEmpty();
  }

  @Test
  void rejectsFakeAndUnprovenOverloadedSelectionExchangeHelpers() {
    String fake =
        """
        private static void exchange(int[] values, int left, int right) { values[left] = values[right]; }
        void solve(int[] data) {
          for (int pass = 0; pass < data.length - 1; pass++) {
            int selected = pass;
            for (int scan = pass + 1; scan < data.length; scan++) {
              if (data[scan] < data[selected]) selected = scan;
            }
            if (selected != pass) exchange(data, pass, selected);
          }
        }
        """;
    String ambiguous =
        fake.replace(
            "private static void exchange(int[] values, int left, int right) { values[left] = values[right]; }",
            """
            private static void exchange(int[] values, int left, int right) {
              int saved = values[left]; values[left] = values[right]; values[right] = saved;
            }
            private static void exchange(int[] values, Integer left, Integer right) { }
            """)
        .replace("exchange(data, pass, selected);",
            "exchange(data, Integer.valueOf(pass), Integer.valueOf(selected));");

    assertThat(detectSemantic(fake)).isEmpty();
    assertThat(detectSemantic(ambiguous)).isEmpty();
  }
}
