package com.example.astchunker.visualization.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.operation.*;
import com.example.astchunker.model.*;
import com.example.astchunker.model.operation.*;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import java.util.*;
import org.junit.jupiter.api.Test;

class OperationTraceBuilderTest {
  private final AstAnalyzer.Analysis analysis =
      new AstAnalyzer()
          .analyze(
              """
              class Sample { public static void main(String[] args) {
                int[] a = {7,3}; int i=0,j=1;
                { int temp=a[i]; a[i]=a[j]; a[j]=temp; }
              } }
              """);
  private final VariableDeclarator temp = variable("temp");
  private final String innerScope =
      AstAnalyzer.nodeId(
          temp.findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class).orElseThrow());

  private VariableDeclarator variable(String name) {
    return analysis.compilationUnit().findAll(VariableDeclarator.class).stream()
        .filter(v -> v.getNameAsString().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private OperationSite site(
      int marker,
      String operation,
      String phase,
      String kind,
      Node node,
      Map<String, String> attributes,
      boolean includeTemp) {
    var r = node.getRange().orElseThrow();
    List<String> ids = new ArrayList<>();
    for (String name : List.of("a", "i", "j")) ids.add(AstAnalyzer.nodeId(variable(name)));
    if (includeTemp) ids.add(AstAnalyzer.nodeId(temp));
    return new OperationSite(
        marker,
        operation,
        phase,
        kind,
        new ObservationPoint(
            AstAnalyzer.nodeId(node),
            "Sample",
            r.begin.line,
            r.begin.line,
            r.end.line,
            r.begin.column,
            r.end.column,
            kind,
            node.toString()),
        ids,
        List.of(innerScope),
        attributes);
  }

  private OperationCapture capture(
      long sequence, OperationSite site, List<Integer> array, boolean includeTemp) {
    List<ObservationResult> values = new ArrayList<>();
    values.add(
        new ObservationResult(
            AstAnalyzer.nodeId(variable("a")), "a", "int[]", "array", "array", array, 3, 99L, 99L));
    values.add(
        new ObservationResult(
            AstAnalyzer.nodeId(variable("i")), "i", "int", "0", "primitive", 0, 3));
    values.add(
        new ObservationResult(
            AstAnalyzer.nodeId(variable("j")), "j", "int", "1", "primitive", 1, 3));
    if (includeTemp)
      values.add(
          new ObservationResult(AstAnalyzer.nodeId(temp), "temp", "int", "7", "primitive", 7, 3));
    return new OperationCapture(
        sequence,
        site,
        new ExecutionContext(
            1, "main", "Sample", "main", "([Ljava/lang/String;)V", 1, sequence, 1L),
        values,
        null);
  }

  private OperationRun run(List<OperationCapture> captures) {
    return new OperationRun(captures, List.of(), "COMPLETE", null, Map.of("maxMarkers", 1000));
  }

  @Test
  void temporaryIsCreatedAsSeparateEntityBeforeInitializationAndCopy() {
    var before =
        site(
            1,
            "declare-temp",
            "BEFORE",
            "DECLARATION",
            temp,
            Map.of("declarationId", AstAnalyzer.nodeId(temp), "hasInitializer", "true"),
            false);
    var after = site(2, "declare-temp", "AFTER", "DECLARATION", temp, before.attributes(), true);
    var result =
        new OperationTraceBuilder()
            .build(
                analysis,
                run(
                    List.of(
                        capture(1, before, List.of(7, 3), false),
                        capture(2, after, List.of(7, 3), true))));
    var step = result.steps().get(1);
    assertThat(step.effects())
        .extracting(OperationEffect::type)
        .containsSubsequence("VARIABLE_CREATED", "VALUE_COPIED", "VARIABLE_INITIALIZED");
    var created =
        step.effects().stream()
            .filter(
                e ->
                    e.type().equals("VARIABLE_CREATED")
                        && e.target().get("declarationId").equals(AstAnalyzer.nodeId(temp)))
            .findFirst()
            .orElseThrow();
    assertThat(((OperationVariable) created.after()).valueStatus()).isEqualTo("UNINITIALIZED");
    var shown =
        step.variables().stream().filter(v -> v.name().equals("temp")).findFirst().orElseThrow();
    assertThat(shown.value()).isEqualTo(7);
    assertThat(shown.objectId()).isNull();
    assertThat(step.objects().get("99").values()).containsExactly(7, 3);
  }

  @Test
  void eachWriteAndScopeExitRetainIntermediateStateWithoutAtomicSwap() {
    var writes = analysis.compilationUnit().findAll(AssignExpr.class);
    var declBefore =
        site(
            1,
            "temp",
            "BEFORE",
            "DECLARATION",
            temp,
            Map.of("declarationId", AstAnalyzer.nodeId(temp), "hasInitializer", "true"),
            false);
    var declAfter = site(2, "temp", "AFTER", "DECLARATION", temp, declBefore.attributes(), true);
    var write1 = site(3, "write-1", "BEFORE", "ARRAY_WRITE", writes.get(0), Map.of(), true);
    var write1After = site(4, "write-1", "AFTER", "ARRAY_WRITE", writes.get(0), Map.of(), true);
    var write2 = site(5, "write-2", "BEFORE", "ARRAY_WRITE", writes.get(1), Map.of(), true);
    var write2After = site(6, "write-2", "AFTER", "ARRAY_WRITE", writes.get(1), Map.of(), true);
    var exit = site(7, "scope-exit", "EXIT", "SCOPE", temp, Map.of("scopeId", innerScope), false);
    var result =
        new OperationTraceBuilder()
            .build(
                analysis,
                run(
                    List.of(
                        capture(1, declBefore, List.of(7, 3), false),
                        capture(2, declAfter, List.of(7, 3), true),
                        capture(3, write1, List.of(7, 3), true),
                        capture(4, write1After, List.of(3, 3), true),
                        capture(5, write2, List.of(3, 3), true),
                        capture(6, write2After, List.of(3, 7), true),
                        capture(7, exit, List.of(3, 7), false))));
    assertThat(result.steps()).hasSize(7);
    assertThat(result.steps().get(3).objects().get("99").values()).containsExactly(3, 3);
    assertThat(result.steps().get(5).objects().get("99").values()).containsExactly(3, 7);
    assertThat(result.steps().get(3).effects())
        .filteredOn(e -> e.type().equals("ARRAY_ELEMENT_WRITTEN"))
        .singleElement()
        .satisfies(
            e ->
                assertThat(e.source())
                    .containsEntry("kind", "ARRAY_ELEMENT")
                    .containsEntry("index", 1));
    assertThat(result.steps().get(5).effects())
        .filteredOn(e -> e.type().equals("ARRAY_ELEMENT_WRITTEN"))
        .singleElement()
        .satisfies(
            e ->
                assertThat(e.source())
                    .containsEntry("kind", "VARIABLE")
                    .containsEntry("declarationId", AstAnalyzer.nodeId(temp)));
    assertThat(result.steps().get(3).effects())
        .extracting(OperationEffect::type)
        .contains("ARRAY_ELEMENT_WRITTEN")
        .doesNotContain("SWAP");
    assertThat(result.steps().get(6).effects())
        .anySatisfy(
            e -> {
              assertThat(e.type()).isEqualTo("VARIABLE_DESTROYED");
              assertThat(e.target()).containsEntry("declarationId", AstAnalyzer.nodeId(temp));
            });
    assertThat(result.steps().get(6).variables()).noneMatch(v -> v.name().equals("temp"));
  }

  @Test
  void missingAfterKeepsOperationPendingAndDoesNotInventWrites() {
    var write = analysis.compilationUnit().findAll(AssignExpr.class).get(0);
    var before = site(1, "pending-write", "BEFORE", "ARRAY_WRITE", write, Map.of(), true);
    var result =
        new OperationTraceBuilder()
            .build(
                analysis,
                new OperationRun(
                    List.of(capture(1, before, List.of(7, 3), true)),
                    List.of("limit"),
                    "TRUNCATED",
                    "MAX_MARKERS",
                    Map.of("maxMarkers", 1)));
    assertThat(result.traceInfo().captureStatus()).isEqualTo("TRUNCATED");
    assertThat(result.steps().get(0).status()).isEqualTo("PENDING");
    assertThat(result.steps().get(0).effects())
        .noneMatch(e -> e.type().equals("ARRAY_ELEMENT_WRITTEN"));
  }

  @Test
  void malformedArrayProjectionIsUnknownRatherThanAnInventedEmptyArray() {
    var site = site(1, "unknown-array", "BEFORE", "EXPRESSION", temp, Map.of(), false);
    var valid = capture(1, site, List.of(7, 3), false);
    var values = new ArrayList<>(valid.variables());
    var a = values.get(0);
    values.set(
        0,
        new ObservationResult(
            a.astNodeId(),
            a.variableName(),
            a.declaredType(),
            "unavailable",
            "array",
            null,
            3,
            99L,
            99L));
    var result =
        new OperationTraceBuilder()
            .build(
                analysis,
                run(List.of(new OperationCapture(1, site, valid.context(), values, null))));
    assertThat(result.steps().get(0).objects().get("99").status()).isEqualTo("UNKNOWN");
    assertThat(result.steps().get(0).variables())
        .anySatisfy(
            v -> {
              assertThat(v.name()).isEqualTo("a");
              assertThat(v.valueStatus()).isEqualTo("UNAVAILABLE");
            });
  }

  @Test
  void unavailableArrayReadInvalidatesPriorProjectionBeforeAttributingAWrite() {
    var write = analysis.compilationUnit().findAll(AssignExpr.class).get(0);
    var first = site(1, "earlier", "BEFORE", "EXPRESSION", temp, Map.of(), true);
    var before = site(2, "write", "BEFORE", "ARRAY_WRITE", write, Map.of(), true);
    var after = site(3, "write", "AFTER", "ARRAY_WRITE", write, Map.of(), true);
    var missing = capture(2, before, List.of(7, 3), true);
    var values = new ArrayList<>(missing.variables());
    var a = values.get(0);
    values.set(
        0,
        new ObservationResult(
            a.astNodeId(),
            a.variableName(),
            a.declaredType(),
            "unavailable",
            "unavailable",
            null,
            3,
            null,
            null));
    var result =
        new OperationTraceBuilder()
            .build(
                analysis,
                run(
                    List.of(
                        capture(1, first, List.of(7, 3), true),
                        new OperationCapture(2, before, missing.context(), values, null),
                        capture(3, after, List.of(3, 3), true))));
    assertThat(result.steps().get(1).objects().get("99").status()).isEqualTo("UNKNOWN");
    assertThat(result.steps().get(2).effects())
        .noneMatch(e -> e.type().equals("ARRAY_ELEMENT_WRITTEN"));
  }

  @Test
  void snapshotValueBudgetTruncatesBeforeOversizedSnapshot() {
    var site = site(1, "large", "BEFORE", "EXPRESSION", temp, Map.of(), false);
    var result =
        new OperationTraceBuilder()
            .build(analysis, run(List.of(capture(1, site, Collections.nCopies(500001, 7), false))));
    assertThat(result.traceInfo().captureStatus()).isEqualTo("TRUNCATED");
    assertThat(result.traceInfo().terminationReason()).isEqualTo("MAX_SNAPSHOT_VALUES");
    assertThat(result.steps()).isEmpty();
    assertThat(result.traceInfo().limits())
        .containsKeys("maxSnapshotValues", "maxSnapshotTextChars");
  }

  @Test
  void cumulativeSnapshotTextBudgetPreservesOnlyConfirmedPrefix() {
    var site = site(1, "text", "BEFORE", "EXPRESSION", temp, Map.of(), false);
    var ordinary = capture(1, site, List.of(7, 3), false);
    List<ObservationResult> values = new ArrayList<>(ordinary.variables());
    var index = values.get(1);
    values.set(
        1,
        new ObservationResult(
            index.astNodeId(), "i", "String", "text", "string", "x".repeat(1000000), 3));
    List<OperationCapture> captures = new ArrayList<>();
    for (int i = 1; i <= 8; i++)
      captures.add(new OperationCapture(i, site, ordinary.context(), values, null));
    var result = new OperationTraceBuilder().build(analysis, run(captures));
    assertThat(result.traceInfo().captureStatus()).isEqualTo("TRUNCATED");
    assertThat(result.traceInfo().terminationReason()).isEqualTo("MAX_SNAPSHOT_TEXT_CHARS");
    assertThat(result.steps()).isNotEmpty().hasSizeLessThan(captures.size());
    assertThat(result.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("PENDING"));
  }
}
