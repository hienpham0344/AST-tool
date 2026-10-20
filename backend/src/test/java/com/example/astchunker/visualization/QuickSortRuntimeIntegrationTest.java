package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.BreakpointMapper;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.ExecutionContext;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class QuickSortRuntimeIntegrationTest {
  private static final String SOURCE = """
      public class QuickSample {
        public static void main(String[] args) {
          int[][] cases = {{}, {7}, {1, 2, 3}, {3, 2, 1}, {4, 2, 4, 1, 2}, {0, -3, 2, -1}};
          for (int[] data : cases) {
            quick(data, 0, data.length - 1);
            System.out.println(java.util.Arrays.toString(data));
          }
        }
        private static void quick(int[] data, int low, int high) {
          if (low >= high) return;
          int pivot = data[high];
          int store = low;
          for (int scan = low; scan < high; scan++) {
            if (data[scan] <= pivot) {
              int saved = data[store];
              data[store] = data[scan];
              data[scan] = saved;
              store++;
            }
          }
          int finalSaved = data[store];
          data[store] = data[high];
          data[high] = finalSaved;
          quick(data, low, store - 1);
          quick(data, store + 1, high);
        }
      }
      """;

  private List<ExecutionObservation> trace(String source) {
    AstAnalyzer parser = new AstAnalyzer();
    var analysis = parser.analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints).singleElement().satisfies(hint -> assertThat(hint.type()).isEqualTo("quick-sort"));
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var observed = new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(30))
          .observe(target, analysis);
      assertThat(observed.warnings()).noneMatch(warning -> warning.contains("timed out"));
      return new VisualTraceBuilder().build(analysis, hints, observed.executions());
    }
  }

  @Test
  void tracksRecursiveFramesAndVerifiesEachCompletedArray() {
    List<ExecutionObservation> steps = trace(SOURCE);
    var quickSteps = steps.stream().filter(step -> step.context() != null
        && step.context().methodName().equals("quick")).toList();
    assertThat(quickSteps).isNotEmpty().allSatisfy(step -> {
      assertThat(step.visualStates()).hasSize(1);
      assertThat(step.visualStates().get(0).quickFrame().invocationId())
          .isEqualTo(step.context().invocationId());
    });
    var frames = quickSteps.stream().map(step -> step.visualStates().get(0).quickFrame()).toList();
    assertThat(frames).filteredOn(frame -> frame.comparison() != null)
        .anySatisfy(frame -> assertThat(frame.comparison().status()).isEqualTo("AVAILABLE_FROM_SNAPSHOT"));
    assertThat(frames).filteredOn(frame -> frame.pendingMutation() != null).isNotEmpty();
    assertThat(frames).filteredOn(frame -> frame.completedMutation() != null)
        .anySatisfy(frame -> assertThat(frame.completedMutation().status())
            .isEqualTo("OBSERVED_AFTER_WRITE"));
    assertThat(frames).anySatisfy(frame ->
        assertThat(frame.partitionStatus()).isEqualTo("VERIFIED_PARTITION"));
    var endings = steps.stream().filter(step -> step.code().startsWith("System.out.println"))
        .toList();
    assertThat(endings).hasSize(6);
    assertThat(endings).extracting(step -> step.visualStates().get(0).array().values())
        .containsExactly(List.of(), List.of(7), List.of(1, 2, 3), List.of(1, 2, 3),
            List.of(1, 2, 2, 4, 4), List.of(-3, -1, 0, 2));
    assertThat(endings).allSatisfy(step -> {
      assertThat(step.visualStates()).hasSize(1);
      assertThat(step.visualStates().get(0).quickFrame().fullArrayStatus())
          .isEqualTo("VERIFIED_SORTED");
    });
    assertThat(frames.stream().map(frame -> frame.invocationId()).filter(Objects::nonNull))
        .hasSizeGreaterThan(6).doesNotContainNull();
  }

  @Test
  void verifiesArraysAfterGuardedQuickBody() {
    String source = SOURCE.replace("if (low >= high) return;", "if (low < high) {");
    int methodEnd = source.lastIndexOf("\n  }\n}");
    assertThat(methodEnd).isPositive();
    source = source.substring(0, methodEnd + 1) + "    }" + source.substring(methodEnd);
    var endings = trace(source).stream()
        .filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(6).allSatisfy(step ->
        assertThat(step.visualStates().get(0).quickFrame().fullArrayStatus())
            .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void reversedOperandsKeepPartitionDecisionsAndFinalOrder() {
    for (String predicate : List.of("pivot >= data[scan]", "pivot <= data[scan]")) {
      var steps = trace(SOURCE.replace("data[scan] <= pivot", predicate));
      boolean ascending = predicate.contains(">=");
      var comparisons = steps.stream()
          .flatMap(step -> step.visualStates().stream())
          .filter(state -> state.quickFrame() != null
              && state.quickFrame().comparison() != null)
          .map(state -> state.quickFrame().comparison()).toList();
      assertThat(comparisons).isNotEmpty().anyMatch(comparison -> comparison.actionRequired())
          .anyMatch(comparison -> !comparison.actionRequired());
      assertThat(comparisons).allSatisfy(comparison -> {
        int current = ((Number) comparison.firstValue()).intValue();
        int pivot = ((Number) comparison.secondValue()).intValue();
        assertThat(comparison.actionRequired())
            .isEqualTo(ascending ? current <= pivot : current >= pivot);
      });
      var endings = steps.stream()
          .filter(step -> step.code().startsWith("System.out.println")).toList();
      assertThat(endings).hasSize(6).allSatisfy(step ->
          assertThat(step.visualStates().get(0).quickFrame().fullArrayStatus())
              .isEqualTo("VERIFIED_SORTED"));
    }
  }

  @Test
  void combinedStructureRewritesStillVerifyAllArrays() {
    String source = SOURCE.replace("if (low >= high) return;", "if (low < high) {")
        .replace("data[scan] <= pivot", "pivot >= data[scan]")
        .replace("store++;", "store = store + 1;")
        .replace("quick(data, low, store - 1);", "QuickSample.quick(data, low, store - 1);")
        .replace("quick(data, store + 1, high);", "QuickSample.quick(data, store + 1, high);");
    int methodEnd = source.lastIndexOf("\n  }\n}");
    assertThat(methodEnd).isPositive();
    source = source.substring(0, methodEnd + 1) + "    }" + source.substring(methodEnd);
    var endings = trace(source).stream()
        .filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(6).allSatisfy(step ->
        assertThat(step.visualStates().get(0).quickFrame().fullArrayStatus())
            .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void verifiesCallerOfPrivateInstanceQuickMethod() {
    String source = SOURCE.replace("quick(data, 0, data.length - 1);",
            "QuickSample sorter = new QuickSample();\n      sorter.quick(data, 0, data.length - 1);")
        .replace("private static void quick", "private void quick")
        .replace("quick(data, low, store - 1);", "this.quick(data, low, store - 1);")
        .replace("quick(data, store + 1, high);", "this.quick(data, store + 1, high);");
    var endings = trace(source).stream()
        .filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(6).allSatisfy(step ->
        assertThat(step.visualStates().get(0).quickFrame().fullArrayStatus())
            .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void verifiesDescendingQuickOnRepeatedArrays() {
    var steps = trace(SOURCE.replace("data[scan] <= pivot", "data[scan] >= pivot"));
    var endings = steps.stream().filter(step -> step.code().startsWith("System.out.println"))
        .toList();
    assertThat(endings).hasSize(6);
    assertThat(endings).extracting(step -> step.visualStates().get(0).array().values())
        .containsExactly(List.of(), List.of(7), List.of(3, 2, 1), List.of(3, 2, 1),
            List.of(4, 4, 2, 2, 1), List.of(2, 0, -1, -3));
    assertThat(endings).allSatisfy(step ->
        assertThat(step.visualStates().get(0).quickFrame().fullArrayStatus())
            .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void whileScanUsesTheSameInvocationScopedTrace() {
    String whileSource = SOURCE
        .replace("for (int scan = low; scan < high; scan++) {",
            "int scan = low;\n    while (scan < high) {")
        .replace("        store++;\n      }\n    }\n    int finalSaved",
            "        store++;\n      }\n      scan++;\n    }\n    int finalSaved");
    assertThat(whileSource).contains("scan++;\n    }\n    int finalSaved");
    var steps = trace(whileSource);
    var endings = steps.stream().filter(step -> step.code().startsWith("System.out.println"))
        .toList();
    assertThat(endings).extracting(step -> step.visualStates().get(0).array().values())
        .containsExactly(List.of(), List.of(7), List.of(1, 2, 3), List.of(1, 2, 3),
            List.of(1, 2, 2, 4, 4), List.of(-3, -1, 0, 2));
  }

  @Test
  void verifiesDirectCallWithRuntimeBoundVariable() {
    String source = SOURCE.replace("quick(data, 0, data.length - 1);",
        "int end = data.length - 1;\n        quick(data, 0, end);");
    var endings = trace(source).stream()
        .filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(6).allSatisfy(step ->
        assertThat(step.visualStates().get(0).quickFrame().fullArrayStatus())
            .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void verifiesCallerAfterWrapperEndingAtQuickCall() {
    String source = SOURCE.replace("quick(data, 0, data.length - 1);", "sort(data);");
    source = source.replace("  private static void quick(int[] data, int low, int high) {",
        "  private static void sort(int[] data) {\n"
            + "    int end = data.length - 1;\n"
            + "    quick(data, 0, end);\n"
            + "  }\n"
            + "  private static void quick(int[] data, int low, int high) {");
    var endings = trace(source).stream()
        .filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(6).allSatisfy(step ->
        assertThat(step.visualStates().get(0).quickFrame().fullArrayStatus())
            .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void linksWrapperResultToRootThatSortedTheSameArray() {
    String source = SOURCE.replace("quick(data, 0, data.length - 1);", "sort(data);")
        .replace("  private static void quick(int[] data, int low, int high) {",
            "  private static void sort(int[] data) {\n"
                + "    int[] scratch = {2, 1};\n"
                + "    quick(scratch, 0, scratch.length - 1);\n"
                + "    quick(data, 0, data.length - 1);\n"
                + "  }\n"
                + "  private static void quick(int[] data, int low, int high) {");
    var steps = trace(source);
    var endings = steps.stream()
        .filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(6).allSatisfy(ending -> {
      Long arrayId = ending.variables().stream()
          .filter(variable -> variable.variableName().equals("data"))
          .findFirst().orElseThrow().arrayObjectId();
      assertThat(arrayId).isNotNull();
      var matchingRoots = steps.stream().filter(step -> step.sequence() < ending.sequence()
          && step.context() != null && step.context().methodName().equals("quick")
          && step.variables().stream().anyMatch(variable ->
              variable.variableName().equals("data")
                  && Objects.equals(variable.arrayObjectId(), arrayId)
                  && variable.arrayObjectId() != null))
          .map(step -> step.context().invocationId()).distinct().toList();
      assertThat(matchingRoots).isNotEmpty();
      assertThat(ending.visualStates().get(0).quickFrame().invocationId())
          .isEqualTo(matchingRoots.get(0));
      assertThat(ending.visualStates().get(0).quickFrame().fullArrayStatus())
          .isEqualTo("VERIFIED_SORTED");
    });
  }

  @Test
  void linksRepeatedRootsOnSameArrayToLastInvocation() {
    String source = SOURCE.replace("quick(data, 0, data.length - 1);", "sort(data);")
        .replace("  private static void quick(int[] data, int low, int high) {",
            "  private static void sort(int[] data) {\n"
                + "    quick(data, 0, data.length - 1);\n"
                + "    quick(data, 0, data.length - 1);\n"
                + "  }\n"
                + "  private static void quick(int[] data, int low, int high) {");
    var steps = trace(source);
    var ending = steps.stream()
        .filter(step -> step.code().startsWith("System.out.println")).toList().get(3);
    Long arrayId = ending.variables().stream()
        .filter(variable -> variable.variableName().equals("data"))
        .findFirst().orElseThrow().arrayObjectId();
    int length = ending.visualStates().get(0).array().values().size();
    var roots = steps.stream().filter(step -> step.sequence() < ending.sequence()
        && step.context() != null && step.context().methodName().equals("quick")
        && !step.visualStates().isEmpty()
        && Objects.equals(step.visualStates().get(0).quickFrame().low(), 0)
        && Objects.equals(step.visualStates().get(0).quickFrame().high(), length - 1)
        && step.variables().stream().anyMatch(variable ->
            variable.variableName().equals("data")
                && Objects.equals(variable.arrayObjectId(), arrayId)
                && variable.arrayObjectId() != null))
        .map(step -> step.context().invocationId()).distinct().toList();
    assertThat(roots).hasSizeGreaterThanOrEqualTo(2);
    assertThat(ending.visualStates().get(0).quickFrame().invocationId())
        .isEqualTo(roots.get(roots.size() - 1));
  }

  @Test
  void verifiesInstanceQuickCalledThroughThis() {
    String source = SOURCE.replace("quick(data, 0, data.length - 1);",
            "new QuickSample().sort(data);")
        .replace("      System.out.println(java.util.Arrays.toString(data));", "")
        .replace("  private static void quick(int[] data, int low, int high) {",
            "  private void sort(int[] data) {\n"
                + "    this.quick(data, 0, data.length - 1);\n"
                + "    System.out.println(java.util.Arrays.toString(data));\n"
                + "  }\n"
                + "  private void quick(int[] data, int low, int high) {");
    var endings = trace(source).stream()
        .filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(6).allSatisfy(step ->
        assertThat(step.visualStates().get(0).quickFrame().fullArrayStatus())
            .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void ignoresUnrelatedScopedQuickCall() {
    String source = SOURCE.replace("quick(data, 0, data.length - 1);",
        "Other.quick(data, 0, data.length - 1);")
        .replace("  private static void quick(int[] data, int low, int high) {",
            "  static class Other { static void quick(int[] data, int low, int high) {} }\n"
                + "  private static void quick(int[] data, int low, int high) {");
    var endings = trace(source).stream()
        .filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(6).allSatisfy(step ->
        assertThat(step.visualStates()).isEmpty());
  }

  @Test
  void confirmsWriteAcrossInterleavedObservationFromAnotherThread() {
    AstAnalyzer parser = new AstAnalyzer();
    var analysis = parser.analyze(SOURCE);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    List<ExecutionObservation> raw;
    try (var target = new TargetCompiler().compile(SOURCE, analysis)) {
      raw = new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
          .observe(target, analysis).executions();
    }
    String rightWrite = hints.get(0).quick().operationAstNodeIds().get("innerSwapRightWrite");
    int insertion = -1;
    for (int index = 0; index < raw.size() - 1; index++) {
      if (raw.get(index).statementAstNodeId().equals(rightWrite)
          && Objects.equals(raw.get(index).context().invocationId(),
              raw.get(index + 1).context().invocationId())) {
        insertion = index + 1;
        break;
      }
    }
    assertThat(insertion).isPositive();
    var before = raw.get(insertion - 1);
    var context = new ExecutionContext(999_999L, "other", analysis.mainClassName(),
        "noise", "()V", 1, 0, 999L);
    var interleaved = new ExecutionObservation(1, before.statementAstNodeId(),
        before.lineNumber(), before.startLine(), before.endLine(), before.startColumn(),
        before.endColumn(), before.statementKind(), before.code(), List.of(), context);
    var mixed = new java.util.ArrayList<ExecutionObservation>();
    mixed.addAll(raw.subList(0, insertion));
    mixed.add(interleaved);
    mixed.addAll(raw.subList(insertion, raw.size()));
    var resequenced = new java.util.ArrayList<ExecutionObservation>();
    for (int index = 0; index < mixed.size(); index++) {
      var step = mixed.get(index);
      resequenced.add(new ExecutionObservation(index + 1L, step.statementAstNodeId(),
          step.lineNumber(), step.startLine(), step.endLine(), step.startColumn(),
          step.endColumn(), step.statementKind(), step.code(), step.variables(), step.context()));
    }
    var enriched = new VisualTraceBuilder().build(analysis, hints, resequenced);
    assertThat(enriched.get(insertion + 1).visualStates().get(0).quickFrame()
        .completedMutation()).isNotNull();
  }

  @Test
  void exposesTruncatedSnapshotWithoutClaimingFullVerification() {
    String source = SOURCE.replace(
        "int[][] cases = {{}, {7}, {1, 2, 3}, {3, 2, 1}, {4, 2, 4, 1, 2}, {0, -3, 2, -1}};",
        "int[][] cases = {new int[101]};")
        .replace("quick(data, 0, data.length - 1);",
            "for (int i = 0; i < data.length; i++) data[i] = (i * 37) % 101;\n"
                + "      quick(data, 0, data.length - 1);");
    var steps = trace(source);
    assertThat(steps).anySatisfy(step ->
        assertThat(step.visualStates()).anySatisfy(state -> {
          assertThat(state.array().truncated()).isTrue();
          assertThat(state.status()).isEqualTo("TRUNCATED");
          assertThat(state.array().values()).hasSize(100);
          assertThat(state.array().length()).isEqualTo(101);
          assertThat(state.quickFrame().fullArrayStatus()).isEqualTo("UNKNOWN");
        }));
  }
}
