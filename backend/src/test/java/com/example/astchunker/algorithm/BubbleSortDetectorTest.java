package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.algorithm.sort.SortPatternRegistry;
import com.example.astchunker.model.AlgorithmHint;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForStmt;
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

  private SortPatternRegistry.Evaluation semantic(String body, String helpers) {
    return semanticFixture(body, helpers).result();
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
