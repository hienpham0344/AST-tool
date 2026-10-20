package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionContext;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.ObservationResult;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.IfStmt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinearSearchTraceBuilderTest {
  private final AstAnalyzer.Analysis analysis = new AstAnalyzer().analyze("""
      class Sample { int find(int[] data, int target) {
        for (int index=0; index<data.length; index++) {
          if (data[index] == target) return index;
        }
        return -1;
      } }
      """);
  private final List<AlgorithmHint> hints =
      new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
  private final Node point = analysis.compilationUnit().findFirst(IfStmt.class).orElseThrow();
  private final Map<String, Node> nodes = nodes();

  private Map<String, Node> nodes() {
    Map<String, Node> result = new LinkedHashMap<>();
    analysis.compilationUnit().walk(node -> result.put(AstAnalyzer.nodeId(node), node));
    return result;
  }

  private ExecutionObservation step(long sequence, Object input, String type,
                                    Long objectId, Integer index, Integer target) {
    AlgorithmHint hint = hints.get(0);
    int line = point.getBegin().orElseThrow().line;
    List<ObservationResult> values = new ArrayList<>();
    values.add(new ObservationResult(hint.variableDeclarationIds().get("data"), "data", "int[]",
        String.valueOf(input), type, input, line, objectId, objectId));
    if (index != null) values.add(new ObservationResult(hint.variableDeclarationIds().get("scan"),
        "index", "int", String.valueOf(index), "primitive", index, line));
    if (target != null) values.add(new ObservationResult(hint.variableDeclarationIds().get("target"),
        "target", "int", String.valueOf(target), "primitive", target, line));
    return new ExecutionObservation(sequence, AstAnalyzer.nodeId(point), line, line, line,
        1, 60, "IfStmt", point.toString(), values,
        new ExecutionContext(1, "main", "Sample", "find", "([II)I", 1, sequence, 7L));
  }

  @Test
  void reportsCandidateComparisonFromCurrentSnapshot() {
    var trace = new LinearSearchTraceBuilder(hints, nodes);
    assertThat(trace.states(step(1, List.of(3, 5), "array", 99L, 0, 5),
        point, "Sample")).singleElement().satisfies(state -> {
          assertThat(state.linearSearchFrame().comparisonStatus()).isEqualTo("DIFFERENT");
          assertThat(state.linearSearchFrame().currentValue()).isEqualTo(3);
        });
    assertThat(trace.states(step(2, List.of(3, 5), "array", 99L, 1, 5),
        point, "Sample")).singleElement().satisfies(state ->
            assertThat(state.linearSearchFrame().comparisonStatus()).isEqualTo("MATCH"));
  }

  @Test
  void missingAndTruncatedValuesStayUnknown() {
    var trace = new LinearSearchTraceBuilder(hints, nodes);
    assertThat(trace.states(step(1, null, "unavailable", 99L, 0, 5),
        point, "Sample")).singleElement().satisfies(state ->
            assertThat(state.linearSearchFrame().comparisonStatus()).isEqualTo("UNKNOWN"));
    assertThat(trace.states(step(2,
        List.of(3, Map.of("truncated", true, "length", 200)), "array", 99L, 1, 5),
        point, "Sample")).singleElement().satisfies(state ->
            assertThat(state.linearSearchFrame().comparisonStatus()).isEqualTo("TRUNCATED"));
  }
}
