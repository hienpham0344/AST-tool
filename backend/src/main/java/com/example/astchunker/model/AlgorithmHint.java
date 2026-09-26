package com.example.astchunker.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A heuristic visualization suggestion scoped to a source range, not a correctness proof. */
public record AlgorithmHint(
    String type,
    double confidence,
    String visualPlan,
    List<String> evidence,
    Map<String, String> variables,
    int startLine,
    int endLine) {

  public AlgorithmHint {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(visualPlan, "visualPlan");
    if (type.isBlank() || visualPlan.isBlank()) {
      throw new IllegalArgumentException("type and visualPlan must not be blank");
    }
    if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
      throw new IllegalArgumentException("confidence must be finite and between 0 and 1");
    }
    if (startLine < 1 || endLine < startLine) {
      throw new IllegalArgumentException("source range must use ordered, positive line numbers");
    }
    evidence = List.copyOf(evidence);
    variables = Map.copyOf(variables);
  }
}
