package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionContext;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.ObservationResult;
import com.example.astchunker.model.SortPattern.Direction;
import com.example.astchunker.model.VisualState.ArrayValue;
import com.github.javaparser.ast.Node;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeapTraceBuilderRangeTest {
  @Test
  void missingAndTruncatedValuesNeverVerifyHeapOrSortedSuffix() {
    ArrayValue missing = new ArrayValue("data", "array", "UNAVAILABLE", List.of(), null, false);
    ArrayValue truncated =
        new ArrayValue(
            "data",
            "array",
            "AVAILABLE",
            List.of(3, 2, Map.of("truncated", true, "length", 4)),
            4,
            true);
    assertThat(HeapTraceBuilder.heapStatus(missing, 3, Direction.ASCENDING)).isEqualTo("UNKNOWN");
    assertThat(HeapTraceBuilder.sortedStatus(missing, 0, null, Direction.ASCENDING))
        .isEqualTo("UNKNOWN");
    assertThat(HeapTraceBuilder.heapStatus(truncated, 3, Direction.ASCENDING))
        .isEqualTo("TRUNCATED");
    assertThat(HeapTraceBuilder.sortedStatus(truncated, 2, 4, Direction.ASCENDING))
        .isEqualTo("TRUNCATED");
  }

  @Test
  void checksAllChildrenAndSuffixOrderInBothDirections() {
    ArrayValue maxHeap =
        new ArrayValue("data", "array", "AVAILABLE", List.of(9, 4, 7, 1, 3), 5, false);
    ArrayValue brokenHeap =
        new ArrayValue("data", "array", "AVAILABLE", List.of(9, 4, 10, 1, 3), 5, false);
    ArrayValue ascending =
        new ArrayValue("data", "array", "AVAILABLE", List.of(-2, 1, 1, 8), 4, false);
    assertThat(HeapTraceBuilder.heapStatus(maxHeap, 5, Direction.ASCENDING))
        .isEqualTo("VERIFIED_HEAP");
    assertThat(HeapTraceBuilder.heapStatus(brokenHeap, 5, Direction.ASCENDING))
        .isEqualTo("NOT_HEAP");
    assertThat(HeapTraceBuilder.sortedStatus(ascending, 0, 4, Direction.ASCENDING))
        .isEqualTo("VERIFIED_SORTED");
    assertThat(HeapTraceBuilder.sortedStatus(ascending, 0, 4, Direction.DESCENDING))
        .isEqualTo("NOT_SORTED");
  }

  @Test
  void anotherArrayOrInvocationCannotConfirmAPendingRootSwap() throws Exception {
    String source = Files.readString(Path.of("../examples/sort/HeapSortSample.java"));
    var analysis = new AstAnalyzer().analyze(source);
    AlgorithmHint hint = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit()).get(0);
    Map<String, Node> nodes = new LinkedHashMap<>();
    analysis.compilationUnit().walk(node -> nodes.put(AstAnalyzer.nodeId(node), node));
    HeapTraceBuilder builder = new HeapTraceBuilder(List.of(hint), nodes);
    String first = hint.heap().operationAstNodeIds().get("rootSwapRootWrite");
    String second = hint.heap().operationAstNodeIds().get("rootSwapEndWrite");
    String restore = hint.heap().operationAstNodeIds().get("restoreCall");
    var pending =
        builder.states(
            observation(hint, 1, first, 11, 41, List.of(3, 1, 2)),
            nodes.get(first),
            "HeapSortSample");
    assertThat(pending.get(0).heapFrame().pendingMutation()).isNotNull();
    assertThat(pending.get(0).heapFrame().verifiedSuffixStart()).isNull();
    assertThat(pending.get(0).heapFrame().suffixStatus()).isEqualTo("UNKNOWN");
    assertThat(pending.get(0).heapFrame().heapStatus()).isEqualTo("UNKNOWN");
    var otherArray =
        builder.states(
            observation(hint, 2, second, 11, 42, List.of(2, 1, 2)),
            nodes.get(second),
            "HeapSortSample");
    assertThat(otherArray.get(0).heapFrame().completedMutation()).isNull();
    var otherInvocation =
        builder.states(
            observation(hint, 3, restore, 12, 41, List.of(2, 1, 3)),
            nodes.get(restore),
            "HeapSortSample");
    assertThat(otherInvocation.get(0).heapFrame().completedMutation()).isNull();
    var correctFirstWrite =
        builder.states(
            observation(hint, 4, second, 11, 41, List.of(2, 1, 2)),
            nodes.get(second),
            "HeapSortSample");
    assertThat(correctFirstWrite.get(0).heapFrame().completedMutation()).isNull();
    var confirmed =
        builder.states(
            observation(hint, 5, restore, 11, 41, List.of(2, 1, 3)),
            nodes.get(restore),
            "HeapSortSample");
    assertThat(confirmed.get(0).heapFrame().completedMutation().kind())
        .isEqualTo("ROOT_SWAP_COMPLETED");
    assertThat(confirmed.get(0).heapFrame().verifiedSuffixStart()).isEqualTo(2);
    assertThat(confirmed.get(0).heapFrame().suffixStatus()).isEqualTo("VERIFIED_SORTED");
  }

  private static ExecutionObservation observation(
      AlgorithmHint hint,
      long sequence,
      String point,
      long invocation,
      long arrayId,
      List<Object> values) {
    List<ObservationResult> variables =
        List.of(
            new ObservationResult(
                hint.variableDeclarationIds().get("array"),
                hint.variables().get("array"),
                "int[]",
                values.toString(),
                "array",
                values,
                1,
                arrayId),
            new ObservationResult(
                hint.variableDeclarationIds().get("size"),
                hint.variables().get("size"),
                "int",
                "3",
                "number",
                3,
                1),
            new ObservationResult(
                hint.variableDeclarationIds().get("end"),
                hint.variables().get("end"),
                "int",
                "2",
                "number",
                2,
                1));
    return new ExecutionObservation(
        sequence,
        point,
        1,
        1,
        1,
        1,
        1,
        "ExpressionStmt",
        "synthetic",
        variables,
        new ExecutionContext(1, "main", "HeapSortSample", "sort", "([I)V", 2, 0, invocation));
  }
}
