package com.example.astchunker.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.astchunker.model.AlgorithmHint;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DebugStepsResponseTest {
  @Test
  void serializesHintsWithRolesEvidenceAndSourceRange() throws Exception {
    AlgorithmHint hint = new AlgorithmHint("binary-search", 0.9, "array-pointers",
        List.of("Midpoint narrows the search interval"), Map.of("array", "nums"), 3, 12);
    var json = new ObjectMapper().valueToTree(
        new DebugStepsResponse(List.of(), List.of(), List.of(hint)));
    var serialized = json.path("algorithmHints").get(0);
    assertThat(serialized.path("type").asText()).isEqualTo("binary-search");
    assertThat(serialized.path("confidence").asDouble()).isEqualTo(0.9);
    assertThat(serialized.path("visualPlan").asText()).isEqualTo("array-pointers");
    assertThat(serialized.path("variables").path("array").asText()).isEqualTo("nums");
    assertThat(serialized.path("evidence").get(0).asText()).contains("Midpoint");
    assertThat(serialized.path("startLine").asInt()).isEqualTo(3);
    assertThat(serialized.path("endLine").asInt()).isEqualTo(12);
    assertThat(json.path("steps").isArray()).isTrue();
    assertThat(json.path("warnings").isArray()).isTrue();
  }

  @Test
  void snapshotsMutableInputs() {
    var evidence = new ArrayList<>(List.of("loop"));
    var variables = new HashMap<>(Map.of("array", "nums"));
    var hint = new AlgorithmHint("array-traversal", 0.5, "array", evidence, variables, 1, 4);
    var hints = new ArrayList<>(List.of(hint));
    var response = new DebugStepsResponse(List.of(), List.of(), hints);
    evidence.clear();
    variables.clear();
    hints.clear();
    assertThat(response.algorithmHints()).containsExactly(hint);
    assertThat(hint.evidence()).containsExactly("loop");
    assertThat(hint.variables()).containsEntry("array", "nums");
  }

  @Test
  void rejectsInvalidConfidenceAndRanges() {
    for (double confidence : new double[] {-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThatThrownBy(() -> new AlgorithmHint("test", confidence, "array",
          List.of(), Map.of(), 1, 2)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new AlgorithmHint("test", 0.5, "array",
        List.of(), Map.of(), 0, 2)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AlgorithmHint("test", 0.5, "array",
        List.of(), Map.of(), 3, 2)).isInstanceOf(IllegalArgumentException.class);
  }
}
