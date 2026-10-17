package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.algorithm.sort.SortPatternRegistry;
import com.example.astchunker.model.AlgorithmHint;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BubbleSortDetectorTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private final AlgorithmPatternAnalyzer analyzer = new AlgorithmPatternAnalyzer();

  private List<AlgorithmHint> detect(String loop) {
    return analyzer.analyze(
        parser.parseCompilationUnit("class Sample { void solve(int[] data) {\n" + loop + "\n} }"));
  }

  private SortPatternRegistry.Evaluation semantic(String body, String helpers) {
    return semanticFixture(body, helpers).result();
  }

  private SortPatternRegistry.Evaluation semanticTyped(String type, String body, String helpers) {
    return semanticTypedFixture(type, body, helpers).result();
  }

  private SemanticFixture semanticTypedFixture(String type, String body, String helpers) {
    CompilationUnit unit = parser.parseCompilationUnit(
        "class Sample { void solve(" + type + "[] data) {\n" + body + "\n} " + helpers + " }");
    return new SemanticFixture(unit, SortPatternRegistry.defaults().begin(unit)
        .evaluate(unit.findFirst(ForStmt.class).orElseThrow()));
  }

  private SemanticFixture semanticComparatorFixture(String body) {
    return semanticComparatorFixture("E[] data, java.util.Comparator<E> comparator", body);
  }

  private SemanticFixture semanticComparatorFixture(String parameters, String body) {
    CompilationUnit unit = parser.parseCompilationUnit(
        "class Sample<E> { void solve(" + parameters + ") {\n"
            + body + "\n} }");
    return new SemanticFixture(unit, SortPatternRegistry.defaults().begin(unit)
        .evaluate(unit.findFirst(ForStmt.class).orElseThrow()));
  }

  private SortPatternRegistry.Evaluation semanticComparator(String body) {
    return semanticComparatorFixture(body).result();
  }

  private SemanticFixture semanticFixture(String body, String helpers) {
    CompilationUnit unit = parser.parseCompilationUnit(
        "class Sample { void solve(int[] data) {\n" + body + "\n} " + helpers + " }");
    var outer = unit.findFirst(ForStmt.class).orElseThrow();
    return new SemanticFixture(unit, SortPatternRegistry.defaults().begin(unit).evaluate(outer));
  }

  private record SemanticFixture(CompilationUnit unit, SortPatternRegistry.Evaluation result) {}

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

  private String bubbleWithCall(String call) {
    return """
        for (int pass=0; pass<data.length-1; pass++) {
          for (int scan=0; scan<data.length-1-pass; scan++) {
            if (data[scan]>data[scan+1]) { %s }
          }
        }
        """.formatted(call);
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
  void keepsIndependentLoopScopeAndRejectsExtraStatementsInsideSwapBlock() {
    String validIndependentScope = "{ int scan = 0; }\n"
        + bubble("data[scan] > data[scan + 1]", "scan + 1");
    assertThat(detect(validIndependentScope)).singleElement().satisfies(hint -> {
      var outer = parser.parseCompilationUnit(
          "class Sample { void solve(int[] data) {\n" + validIndependentScope
              + "\n} }").findAll(ForStmt.class).get(1);
      String loopScan = AstAnalyzer.nodeId(outer.getInitialization().get(0)
          .asVariableDeclarationExpr().getVariable(0));
      assertThat(hint.variableDeclarationIds()).containsEntry("scan", loopScan);
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"byte", "short", "long", "char", "float", "double"})
  void recognizesNumericPrimitiveBubbleStaticallyWithoutSortFrames(String type) {
    for (String operator : List.of(">", "<")) {
      String body = bubble("data[scan] " + operator + " data[scan + 1]", "scan + 1")
          .replace("int temp", type + " temp");
      var fixture = semanticTypedFixture(type, body, "");
      assertThat(fixture.result().status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
      assertThat(fixture.result().candidates()).singleElement().satisfies(hint -> {
        assertThat(hint.type()).isEqualTo("bubble-sort");
        assertThat(hint.sort()).isNull();
        assertThat(hint.variableDeclarationIds())
            .containsEntry("array", AstAnalyzer.nodeId(fixture.unit().findFirst(Parameter.class).orElseThrow()));
        for (String role : List.of("pass", "scan", "temp")) {
          String id = fixture.unit().findAll(VariableDeclarator.class).stream()
              .filter(variable -> variable.getNameAsString().equals(role))
              .findFirst().map(AstAnalyzer::nodeId).orElseThrow();
          assertThat(hint.variableDeclarationIds()).containsEntry(role, id);
        }
      });
    }
  }

  @Test
  void rejectsBooleanObjectAndMismatchedNumericTemporary() {
    for (String type : List.of("boolean", "String", "int[]", "long[]", "double[]")) {
      String body = bubble("data[scan] > data[scan + 1]", "scan + 1")
          .replace("int temp", type + " temp");
      assertThat(semanticTyped(type, body, "").status())
          .as(type)
          .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    }
    assertThat(semanticTyped("long", bubble("data[scan] > data[scan + 1]", "scan + 1"), "").status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
  }

  @Test
  void recognizesExactLongExchangeHelperButRejectsWidenedHelper() {
    String body = bubbleWithCall("exchange(data, scan, scan + 1);");
    String exact = "private static void exchange(long[] a,int i,int j){long t=a[i];a[i]=a[j];a[j]=t;}";
    var fixture = semanticTypedFixture("long", body, exact);
    String callId = AstAnalyzer.nodeId(fixture.unit().findFirst(MethodCallExpr.class).orElseThrow());
    String helperId = AstAnalyzer.nodeId(fixture.unit().findFirst(MethodDeclaration.class,
        method -> method.getNameAsString().equals("exchange")).orElseThrow());
    assertThat(fixture.result().candidates())
        .singleElement()
        .satisfies(hint -> {
          assertThat(hint.type()).isEqualTo("bubble-sort");
          assertThat(hint.sort()).isNull();
          assertThat(hint.evidence()).anyMatch(item -> item.contains(callId) && item.contains(helperId));
        });
    String wrong = "private static void exchange(int[] a,int i,int j){int t=a[i];a[i]=a[j];a[j]=t;}";
    assertThat(semanticTyped("long", body, wrong).status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
  }

  @Test
  void recognizesDirectComparatorBubbleStaticallyWithExactRoleIds() {
    String body = bubble("comparator.compare(data[scan], data[scan + 1]) > 0", "scan + 1")
        .replace("int temp", "E temp");
    var fixture = semanticComparatorFixture(body);

    assertThat(fixture.result().status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(fixture.result().candidates()).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("bubble-sort");
      assertThat(hint.sort()).isNull();
      assertThat(hint.variableDeclarationIds())
          .containsEntry("array", AstAnalyzer.nodeId(fixture.unit().findFirst(Parameter.class,
              parameter -> parameter.getNameAsString().equals("data")).orElseThrow()))
          .containsEntry("comparator", AstAnalyzer.nodeId(fixture.unit().findFirst(Parameter.class,
              parameter -> parameter.getNameAsString().equals("comparator")).orElseThrow()));
      for (String role : List.of("pass", "scan", "temp")) {
        String id = fixture.unit().findAll(VariableDeclarator.class).stream()
            .filter(variable -> variable.getNameAsString().equals(role))
            .findFirst().map(AstAnalyzer::nodeId).orElseThrow();
        assertThat(hint.variableDeclarationIds()).containsEntry(role, id);
      }
    });
  }

  @Test
  void recognizesReversedComparatorOperandsAndStrictLessAsAscending() {
    String body = bubble("comparator.compare(data[scan + 1], data[scan]) < 0", "scan + 1")
        .replace("int temp", "E temp");
    assertThat(semanticComparator(body).status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(semanticComparator(body).candidates()).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("bubble-sort");
      assertThat(hint.sort()).isNull();
    });
  }

  @Test
  void rejectsComparatorBubbleNearMisses() {
    String canonical = bubble("comparator.compare(data[scan], data[scan + 1]) > 0", "scan + 1")
        .replace("int temp", "E temp");
    assertThat(semanticComparator(canonical.replace("> 0", ">= 0")).status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    assertThat(semanticComparator(canonical.replace("E temp", "Object temp")).status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    assertThat(semanticComparator(canonical.replace("comparator.compare", "comparator.reversed().compare")).status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    assertThat(semanticComparator(canonical.replace("data[scan],", "(E) data[scan],")).status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    assertThat(semanticComparatorFixture("E[] data, java.util.Comparator<String> comparator", canonical)
        .result().status()).isEqualTo(SortPatternRegistry.Status.UNKNOWN);

    String crossArray = canonical
        .replace("data[scan + 1])", "other[scan + 1])");
    assertThat(semanticComparatorFixture("E[] data, E[] other, java.util.Comparator<E> comparator", crossArray)
        .result().status()).isEqualTo(SortPatternRegistry.Status.UNKNOWN);
  }

  @Test
  void recognizesComparatorBubbleThroughASafeSameTypeArrayAlias() {
    String body = "E[] work = data;\n"
        + bubble("comparator.compare(work[scan], work[scan + 1]) > 0", "scan + 1")
            .replace("data", "work")
            .replace("int temp", "E temp");
    var fixture = semanticComparatorFixture(body);
    String workId = AstAnalyzer.nodeId(fixture.unit().findFirst(VariableDeclarator.class,
        variable -> variable.getNameAsString().equals("work")).orElseThrow());

    assertThat(fixture.result().status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(fixture.result().candidates()).singleElement().satisfies(hint -> {
      assertThat(hint.sort()).isNull();
      assertThat(hint.variables()).containsEntry("array", "work");
      assertThat(hint.variableDeclarationIds()).containsEntry("array", workId);
    });
  }

  @Test
  void recognizesDirectNaturalOrderBubbleStaticallyWithExactRoleIds() {
    String body = bubble("data[scan].compareTo(data[scan + 1]) > 0", "scan + 1")
        .replace("int temp", "java.lang.String temp");
    var fixture = semanticTypedFixture("java.lang.String", body, "");
    String dataId = AstAnalyzer.nodeId(fixture.unit().findFirst(Parameter.class,
        parameter -> parameter.getNameAsString().equals("data")).orElseThrow());
    String passId = AstAnalyzer.nodeId(fixture.unit().findFirst(VariableDeclarator.class,
        variable -> variable.getNameAsString().equals("pass")).orElseThrow());
    String scanId = AstAnalyzer.nodeId(fixture.unit().findFirst(VariableDeclarator.class,
        variable -> variable.getNameAsString().equals("scan")).orElseThrow());
    String tempId = AstAnalyzer.nodeId(fixture.unit().findFirst(VariableDeclarator.class,
        variable -> variable.getNameAsString().equals("temp")).orElseThrow());

    assertThat(fixture.result().status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(fixture.result().candidates()).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("bubble-sort");
      assertThat(hint.sort()).isNull();
      assertThat(hint.variableDeclarationIds()).containsExactlyInAnyOrderEntriesOf(
          java.util.Map.of("array", dataId, "pass", passId, "scan", scanId, "temp", tempId));
      assertThat(hint.evidence()).anyMatch(item -> item.contains("natural-order"));
    });
  }

  @Test
  void recognizesNaturalOrderBubbleThroughSafeAliasAndZeroLeftComparison() {
    String body = "java.lang.String[] work = data;\n"
        + bubble("0 < work[scan].compareTo(work[scan + 1])", "scan + 1")
            .replace("data", "work")
            .replace("int temp", "java.lang.String temp");
    var fixture = semanticTypedFixture("java.lang.String", body, "");
    String workId = AstAnalyzer.nodeId(fixture.unit().findFirst(VariableDeclarator.class,
        variable -> variable.getNameAsString().equals("work")).orElseThrow());
    String passId = AstAnalyzer.nodeId(fixture.unit().findFirst(VariableDeclarator.class,
        variable -> variable.getNameAsString().equals("pass")).orElseThrow());
    String scanId = AstAnalyzer.nodeId(fixture.unit().findFirst(VariableDeclarator.class,
        variable -> variable.getNameAsString().equals("scan")).orElseThrow());
    String tempId = AstAnalyzer.nodeId(fixture.unit().findFirst(VariableDeclarator.class,
        variable -> variable.getNameAsString().equals("temp")).orElseThrow());

    assertThat(fixture.result().status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(fixture.result().candidates()).singleElement().satisfies(hint -> {
      assertThat(hint.sort()).isNull();
      assertThat(hint.variables()).containsEntry("array", "work");
      assertThat(hint.variableDeclarationIds()).containsExactlyInAnyOrderEntriesOf(
          java.util.Map.of("array", workId, "pass", passId, "scan", scanId, "temp", tempId));
    });
  }

  @Test
  void rejectsNaturalOrderBubbleNearMisses() {
    String canonical = bubble("data[scan].compareTo(data[scan + 1]) > 0", "scan + 1")
        .replace("int temp", "java.lang.String temp");
    assertThat(semanticTyped("String", canonical.replace("java.lang.String temp", "String temp"), "").status())
        .isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(semanticTyped("java.lang.String", canonical.replace("java.lang.String temp", "Object temp"), "").status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    assertThat(semanticTyped("java.lang.String", canonical.replace("data[scan].compareTo", "((java.lang.String) data[scan]).compareTo"), "").status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    assertThat(semanticTyped("java.lang.String", canonical.replace("data[scan].compareTo", "data[scan].trim().compareTo"), "").status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    String crossArray = canonical.replace("data[scan + 1]", "other[scan + 1]");
    assertThat(semanticNaturalFixture("java.lang.String[] data, java.lang.String[] other", crossArray)
        .result().status()).isEqualTo(SortPatternRegistry.Status.UNKNOWN);
  }

  @ParameterizedTest
  @ValueSource(strings = {"String", "Integer"})
  void doesNotTreatSameFileShortNameShadowAsJdkNaturalOrder(String type) {
    String body = bubble("data[scan].compareTo(data[scan + 1]) > 0", "scan + 1")
        .replace("int temp", type + " temp");
    CompilationUnit unit = parser.parseCompilationUnit("class Sample {"
        + " static final class " + type + " implements Comparable<" + type + "> {"
        + " public int compareTo(" + type + " other) { return 0; } }"
        + " void solve(" + type + "[] data) {\n" + body + "\n} }");

    assertThat(SortPatternRegistry.defaults().begin(unit)
        .evaluate(unit.findFirst(ForStmt.class).orElseThrow()).status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
  }

  private SemanticFixture semanticNaturalFixture(String parameters, String body) {
    CompilationUnit unit = parser.parseCompilationUnit(
        "class Sample { void solve(" + parameters + ") {\n" + body + "\n} }");
    return new SemanticFixture(unit, SortPatternRegistry.defaults().begin(unit)
        .evaluate(unit.findFirst(ForStmt.class).orElseThrow()));
  }

  @Test
  void rejectsNestedReferenceArraysEvenWithAnExactComparatorParameter() {
    String body = bubble("comparator.compare(data[scan], data[scan + 1]) > 0", "scan + 1")
        .replace("int temp", "E[] temp");
    assertThat(semanticComparatorFixture("E[][] data, java.util.Comparator<E[]> comparator", body)
        .result().status()).isEqualTo(SortPatternRegistry.Status.UNKNOWN);
  }

  @Test
  void semanticBubbleAcceptsReversedComparisonAndUnitCounterSyntax() {
    String source = bubble("(data[scan + 1]) < (data[scan])", "scan + 1")
        .replace("scan++)", "scan += 1)");
    var result = semantic(source, "");
    assertThat(result.status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(result.candidates()).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("bubble-sort");
      assertThat(hint.confidence()).isEqualTo(0.95);
      assertThat(hint.sort()).isNotNull();
      assertThat(hint.sort().direction())
          .isEqualTo(com.example.astchunker.model.SortPattern.Direction.ASCENDING);
      assertThat(hint.sort().swapStatementAstNodeIds()).hasSize(3);
    });
  }

  @Test
  void semanticBubbleAcceptsSafeAliasAndProvenSameFileExchange() {
    String alias = "int[] work = data;\n"
        + bubble("work[scan] > work[scan + 1]", "scan + 1").replace("data", "work");
    var direct = semantic(alias, "");
    assertThat(direct.status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(direct.candidates()).singleElement().satisfies(hint -> {
      assertThat(hint.variables()).containsEntry("array", "work");
      assertThat(hint.variableDeclarationIds()).containsKey("array");
      assertThat(hint.sort()).isNotNull();
    });

    String helperBody = bubbleWithCall("exchange(data, scan, scan + 1);");
    String helper = "private static void exchange(int[] a,int i,int j){int t=a[i];a[i]=a[j];a[j]=t;}";
    var helperFixture = semanticFixture(helperBody, helper);
    var resolved = helperFixture.result();
    assertThat(resolved.status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(resolved.candidates()).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("bubble-sort");
      assertThat(hint.sort()).isNull();
      String callId = AstAnalyzer.nodeId(helperFixture.unit().findFirst(MethodCallExpr.class,
          call -> call.getNameAsString().equals("exchange")).orElseThrow());
      String helperId = AstAnalyzer.nodeId(helperFixture.unit().findFirst(MethodDeclaration.class,
          method -> method.getNameAsString().equals("exchange")).orElseThrow());
      assertThat(hint.evidence()).anyMatch(item -> item.contains(callId) && item.contains(helperId));
    });
  }

  @Test
  void semanticBubbleAcceptsExchangeWithReversedAdjacentArguments() {
    String helper = "private static void exchange(int[] a,int i,int j){int t=a[i];a[i]=a[j];a[j]=t;}";
    assertThat(semantic(bubbleWithCall("exchange(data, scan + 1, scan);"), helper).status())
        .isEqualTo(SortPatternRegistry.Status.UNIQUE);
  }

  @Test
  void semanticBubbleAcceptsEarlyExitWhenAProvenHelperExchangeSetsItsFlag() {
    String source = """
        for (int pass=0; pass<data.length-1; pass++) {
          boolean swapped=false;
          for (int scan=0; scan<data.length-1-pass; scan++) {
            if (data[scan]>data[scan+1]) { exchange(data, scan, scan + 1); swapped=true; }
          }
          if (!swapped) break;
        }
        """;
    String helper = "private static void exchange(int[] a,int i,int j){int t=a[i];a[i]=a[j];a[j]=t;}";
    var result = semantic(source, helper);
    assertThat(result.status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(result.candidates()).singleElement().satisfies(hint -> {
      assertThat(hint.sort()).isNull();
      assertThat(hint.variableDeclarationIds()).containsKey("swapped");
      assertThat(hint.evidence()).anyMatch(item -> item.contains("swapped flag"));
    });
  }

  @Test
  void semanticBubbleUsesTheSafeAliasDeclarationForProvenance() {
    String source = "int[] work = data;\n"
        + bubble("work[scan] > work[scan + 1]", "scan + 1").replace("data", "work");
    var fixture = semanticFixture(source, "");
    String workId = AstAnalyzer.nodeId(fixture.unit().findFirst(VariableDeclarator.class,
        variable -> variable.getNameAsString().equals("work")).orElseThrow());
    assertThat(fixture.result().candidates()).singleElement().satisfies(hint ->
        assertThat(hint.variableDeclarationIds()).containsEntry("array", workId));
  }

  @Test
  void semanticBubbleAcceptsPerPassEarlyExitWithProvenFlag() {
    String source = """
        for (int pass=0; pass<data.length-1; pass++) {
          boolean swapped=false;
          for (int scan=0; scan<data.length-1-pass; scan++) {
            if (data[scan]>data[scan+1]) {
              int temp=data[scan]; data[scan]=data[scan+1]; data[scan+1]=temp;
              swapped=true;
            }
          }
          if (!swapped) break;
        }
        """;
    var result = semantic(source, "");
    assertThat(result.status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
    assertThat(result.candidates()).singleElement().satisfies(hint -> {
      assertThat(hint.type()).isEqualTo("bubble-sort");
      assertThat(hint.sort()).isNotNull();
      assertThat(hint.sort().swapStatementAstNodeIds()).hasSize(3);
      assertThat(hint.variableDeclarationIds()).containsKey("swapped");
    });
  }

  @Test
  void semanticBubbleAllowsAnUnrelatedScalarObservation() {
    String source = bubble("data[scan] > data[scan + 1]", "scan + 1")
        .replace("if (data[scan] > data[scan + 1]) {",
            "int observed = scan;\n            if (data[scan] > data[scan + 1]) {");
    assertThat(semantic(source, "").status()).isEqualTo(SortPatternRegistry.Status.UNIQUE);
  }

  @Test
  void semanticBubbleRejectsUnprovenExchangeAndEarlyExit() {
    String fake = bubbleWithCall("exchange(data, scan, scan + 1);");
    assertThat(semantic(fake,
        "private static void exchange(int[] a,int i,int j){a[i]=a[j];}").status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    String unsafe = """
        for (int pass=0; pass<data.length-1; pass++) {
          boolean swapped=false;
          for (int scan=0; scan<data.length-1-pass; scan++) {
            if (data[scan]>data[scan+1]) {
              int temp=data[scan]; data[scan]=data[scan+1]; data[scan+1]=temp;
              swapped=true;
            }
          }
          swapped=true;
          if (!swapped) break;
        }
        """;
    assertThat(semantic(unsafe, "").status()).isEqualTo(SortPatternRegistry.Status.UNKNOWN);
  }

  @Test
  void semanticBubbleRejectsUnsafeBoundsEscapedAliasAndOpaqueComparison() {
    String canonical = bubble("data[scan] > data[scan + 1]", "scan + 1");
    assertThat(semantic(canonical.replace("scan < data.length - 1 - pass",
        "scan <= data.length - 2 - pass"), "").status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    assertThat(semantic(canonical.replace("pass < data.length - 1", "pass < 1"), "").status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    String escaped = "int[] work=data; outside(work);\n"
        + canonical.replace("data", "work");
    assertThat(semantic(escaped, "private void outside(int[] value){}").status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
    String opaque = canonical.replace("data[scan] > data[scan + 1]",
        "greater(data[scan], data[scan + 1])");
    assertThat(semantic(opaque,
        "private boolean greater(int x,int y){touch();return x>y;} private void touch(){}").status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
  }

  @Test
  void semanticBubbleRejectsAnAliasDerivedAfterItsSourceWasReassigned() {
    String source = "int[] first = data; first = new int[1]; int[] work = first;\n"
        + bubble("work[scan] > work[scan + 1]", "scan + 1").replace("data", "work");
    assertThat(semantic(source, "").status()).isEqualTo(SortPatternRegistry.Status.UNKNOWN);
  }

  @Test
  void semanticBubbleRejectsComparatorHelpers() {
    String comparison = bubble("greater(data[scan], data[scan + 1])", "scan + 1");
    assertThat(semantic(comparison,
        "private static boolean greater(int left,int right){return left>right;}").status())
        .isEqualTo(SortPatternRegistry.Status.UNKNOWN);
  }

  @Test
  void semanticBubbleAcceptsDescendingWithReversedComparison() {
    String source = bubble("data[scan + 1] > data[scan]", "scan + 1");
    assertThat(semantic(source, "").candidates()).singleElement().satisfies(hint ->
        assertThat(hint.sort().direction())
            .isEqualTo(com.example.astchunker.model.SortPattern.Direction.DESCENDING));
  }
}
