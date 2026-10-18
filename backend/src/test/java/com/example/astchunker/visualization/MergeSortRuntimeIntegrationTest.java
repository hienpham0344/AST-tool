package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.BreakpointMapper;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.ExecutionObservation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class MergeSortRuntimeIntegrationTest {
  private static final String SOURCE;

  static {
    try {
      SOURCE = Files.readString(Path.of("../examples/sort/MergeSortSample.java"));
    } catch (java.io.IOException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private List<ExecutionObservation> trace(String source) {
    AstAnalyzer parser = new AstAnalyzer();
    var analysis = parser.analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints)
        .singleElement()
        .satisfies(hint -> assertThat(hint.type()).isEqualTo("merge-sort"));
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var observed =
          new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
              .observe(target, analysis);
      assertThat(observed.warnings()).noneMatch(warning -> warning.contains("timed out"));
      return new VisualTraceBuilder().build(analysis, hints, observed.executions());
    }
  }

  @Test
  void tracksAuxiliaryWritesCopyBackAndCompletedArrays() {
    var steps = trace(SOURCE);
    var mergeSteps =
        steps.stream()
            .filter(step -> step.context() != null && step.context().methodName().equals("merge"))
            .toList();
    assertThat(mergeSteps)
        .isNotEmpty()
        .allSatisfy(
            step -> {
              assertThat(step.visualStates()).hasSize(1);
              assertThat(step.visualStates().get(0).mergeFrame().invocationId())
                  .isEqualTo(step.context().invocationId());
            });
    var frames = mergeSteps.stream().map(step -> step.visualStates().get(0).mergeFrame()).toList();
    assertThat(frames).anySatisfy(frame -> assertThat(frame.comparison()).isNotNull());
    var pendingKinds =
        frames.stream()
            .map(frame -> frame.pendingMutation())
            .filter(Objects::nonNull)
            .map(mutation -> mutation.kind())
            .toList();
    var completedKinds =
        frames.stream()
            .map(frame -> frame.completedMutation())
            .filter(Objects::nonNull)
            .map(mutation -> mutation.kind())
            .toList();
    assertThat(pendingKinds).contains("AUX_WRITE_PENDING", "COPY_BACK_PENDING");
    assertThat(completedKinds).contains("AUX_WRITE_COMPLETED", "COPY_BACK_COMPLETED");
    assertThat(frames)
        .anySatisfy(frame -> assertThat(frame.mergedRangeStatus()).isEqualTo("VERIFIED_SORTED"));
    var endings =
        steps.stream().filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(6);
    assertThat(endings)
        .extracting(step -> step.visualStates().get(0).array().values())
        .containsExactly(
            List.of(),
            List.of(7),
            List.of(1, 2, 3),
            List.of(1, 2, 3),
            List.of(1, 2, 2, 4, 4),
            List.of(-3, -1, 0, 2));
    assertThat(endings)
        .allSatisfy(
            step ->
                assertThat(step.visualStates().get(0).mergeFrame().fullArrayStatus())
                    .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void verifiesArraysAfterGuardedMergeBody() {
    String source = SOURCE.replace("if (low >= high) {\n      return;\n    }", "if (low < high) {");
    int methodEnd = source.lastIndexOf("\n  }\n}");
    assertThat(methodEnd).isPositive();
    source = source.substring(0, methodEnd + 1) + "    }" + source.substring(methodEnd);
    var endings =
        trace(source).stream()
            .filter(step -> step.code().startsWith("System.out.println"))
            .toList();
    assertThat(endings)
        .hasSize(6)
        .allSatisfy(
            step ->
                assertThat(step.visualStates().get(0).mergeFrame().fullArrayStatus())
                    .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void reversedOperandsKeepMergeDecisionsAndFinalOrder() {
    for (String predicate : List.of("data[right] >= data[left]", "data[right] <= data[left]")) {
      var steps = trace(SOURCE.replace("data[left] <= data[right]", predicate));
      boolean ascending = predicate.contains(">=");
      var comparisons =
          steps.stream()
              .flatMap(step -> step.visualStates().stream())
              .filter(
                  state -> state.mergeFrame() != null && state.mergeFrame().comparison() != null)
              .map(state -> state.mergeFrame().comparison())
              .toList();
      assertThat(comparisons)
          .isNotEmpty()
          .anyMatch(comparison -> comparison.actionRequired())
          .anyMatch(comparison -> !comparison.actionRequired());
      assertThat(comparisons)
          .allSatisfy(
              comparison -> {
                int left = ((Number) comparison.firstValue()).intValue();
                int right = ((Number) comparison.secondValue()).intValue();
                assertThat(comparison.actionRequired())
                    .isEqualTo(ascending ? left <= right : left >= right);
              });
      var endings =
          steps.stream().filter(step -> step.code().startsWith("System.out.println")).toList();
      assertThat(endings)
          .hasSize(6)
          .allSatisfy(
              step ->
                  assertThat(step.visualStates().get(0).mergeFrame().fullArrayStatus())
                      .isEqualTo("VERIFIED_SORTED"));
    }
  }

  @Test
  void combinedStructureRewritesStillVerifyAllArrays() {
    String source =
        SOURCE
            .replace("if (low >= high) {\n      return;\n    }", "if (low < high) {")
            .replace("data[left] <= data[right]", "data[right] >= data[left]")
            .replace("merge(data, low, mid);", "MergeSortSample.merge(data, low, mid);")
            .replace("merge(data, mid + 1, high);", "MergeSortSample.merge(data, mid + 1, high);");
    int methodEnd = source.lastIndexOf("\n  }\n}");
    assertThat(methodEnd).isPositive();
    source = source.substring(0, methodEnd + 1) + "    }" + source.substring(methodEnd);
    var endings =
        trace(source).stream()
            .filter(step -> step.code().startsWith("System.out.println"))
            .toList();
    assertThat(endings)
        .hasSize(6)
        .allSatisfy(
            step ->
                assertThat(step.visualStates().get(0).mergeFrame().fullArrayStatus())
                    .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void verifiesCallerOfPrivateInstanceMergeMethod() {
    String source =
        SOURCE
            .replace(
                "merge(data, 0, data.length - 1);",
                "MergeSortSample sorter = new MergeSortSample();\n"
                    + "      sorter.merge(data, 0, data.length - 1);")
            .replace("private static void merge", "private void merge")
            .replace("merge(data, low, mid);", "this.merge(data, low, mid);")
            .replace("merge(data, mid + 1, high);", "this.merge(data, mid + 1, high);");
    var endings =
        trace(source).stream()
            .filter(step -> step.code().startsWith("System.out.println"))
            .toList();
    assertThat(endings)
        .hasSize(6)
        .allSatisfy(
            step ->
                assertThat(step.visualStates().get(0).mergeFrame().fullArrayStatus())
                    .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void verifiesDescendingAndDoesNotAdvanceSourceBeforeCopyBack() {
    var steps = trace(SOURCE.replace("data[left] <= data[right]", "data[left] >= data[right]"));
    var endings =
        steps.stream().filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings)
        .extracting(step -> step.visualStates().get(0).array().values())
        .containsExactly(
            List.of(),
            List.of(7),
            List.of(3, 2, 1),
            List.of(3, 2, 1),
            List.of(4, 4, 2, 2, 1),
            List.of(2, 0, -1, -3));
    assertThat(endings)
        .allSatisfy(
            step ->
                assertThat(step.visualStates().get(0).mergeFrame().fullArrayStatus())
                    .isEqualTo("VERIFIED_SORTED"));
    assertThat(steps)
        .anySatisfy(
            step -> {
              if (step.visualStates().isEmpty() || step.visualStates().get(0).mergeFrame() == null)
                throw new AssertionError("No merge state");
              var frame = step.visualStates().get(0).mergeFrame();
              assertThat(frame.pendingMutation()).isNotNull();
              assertThat(frame.pendingMutation().kind()).isEqualTo("AUX_WRITE_PENDING");
              assertThat(step.visualStates().get(0).array().values())
                  .isNotEqualTo(frame.auxiliary().values());
            });
  }

  @Test
  void verifiesWhileCopyBackAndRuntimeBoundVariable() {
    String source =
        SOURCE
            .replace(
                "merge(data, 0, data.length - 1);",
                "int end = data.length - 1;\n      merge(data, 0, end);")
            .replace(
                "for (int index = low; index <= high; index++) {\n"
                    + "      data[index] = aux[index - low];\n    }",
                "int index = low;\n    while (index <= high) {\n"
                    + "      data[index] = aux[index - low];\n      index++;\n    }");
    assertThat(source).contains("while (index <= high)");
    var endings =
        trace(source).stream()
            .filter(step -> step.code().startsWith("System.out.println"))
            .toList();
    assertThat(endings)
        .hasSize(6)
        .allSatisfy(
            step ->
                assertThat(step.visualStates().get(0).mergeFrame().fullArrayStatus())
                    .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void verifiesCallerAfterPassThroughWrapperEndingAtMergeCall() {
    String source =
        SOURCE
            .replace("merge(data, 0, data.length - 1);", "sort(data);")
            .replace(
                "  private static void merge(int[] data, int low, int high) {",
                "  private static void sort(int[] data) {\n"
                    + "    int end = data.length - 1;\n"
                    + "    merge(data, 0, end);\n"
                    + "  }\n"
                    + "  private static void merge(int[] data, int low, int high) {");
    var endings =
        trace(source).stream()
            .filter(step -> step.code().startsWith("System.out.println"))
            .toList();
    assertThat(endings)
        .hasSize(6)
        .allSatisfy(
            step ->
                assertThat(step.visualStates().get(0).mergeFrame().fullArrayStatus())
                    .isEqualTo("VERIFIED_SORTED"));
  }

  @Test
  void linksEachCompletedArrayToItsLatestRootInvocation() {
    String source =
        SOURCE.replace(
            "merge(data, 0, data.length - 1);",
            "int[] scratch = {2, 1};\n"
                + "      merge(scratch, 0, scratch.length - 1);\n"
                + "      merge(data, 0, data.length - 1);\n"
                + "      merge(data, 0, data.length - 1);");
    var steps = trace(source);
    var endings =
        steps.stream().filter(step -> step.code().startsWith("System.out.println")).toList();
    assertThat(endings).hasSize(6);
    for (var ending : endings) {
      var observedArray =
          ending.variables().stream()
              .filter(variable -> variable.variableName().equals("data"))
              .findFirst()
              .orElseThrow();
      int length = ((List<?>) observedArray.visualValue()).size();
      Long latestRoot =
          steps.stream()
              .filter(
                  step ->
                      step.sequence() < ending.sequence()
                          && step.context() != null
                          && step.context().methodName().equals("merge"))
              .filter(
                  step ->
                      step.variables().stream()
                          .anyMatch(
                              variable ->
                                  variable.variableName().equals("data")
                                      && Objects.equals(
                                          variable.arrayObjectId(), observedArray.arrayObjectId())))
              .filter(
                  step ->
                      step.variables().stream()
                              .anyMatch(
                                  variable ->
                                      variable.variableName().equals("low")
                                          && Objects.equals(variable.visualValue(), 0))
                          && step.variables().stream()
                              .anyMatch(
                                  variable ->
                                      variable.variableName().equals("high")
                                          && Objects.equals(variable.visualValue(), length - 1)))
              .map(step -> step.context().invocationId())
              .reduce((first, second) -> second)
              .orElseThrow();
      assertThat(ending.visualStates().get(0).mergeFrame().invocationId()).isEqualTo(latestRoot);
      assertThat(ending.visualStates().get(0).mergeFrame().fullArrayStatus())
          .isEqualTo("VERIFIED_SORTED");
    }
  }
}
