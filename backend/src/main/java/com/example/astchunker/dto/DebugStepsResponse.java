package com.example.astchunker.dto;

import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.visualization.output.NormalizedVisualizationResponse;
import com.example.astchunker.visualization.output.VisualizationOutputMapper;
import java.util.List;

/**
 * Additive grouped execution response for the visualizer; the legacy debug response is unchanged.
 */
public record DebugStepsResponse(
    List<ExecutionObservation> steps,
    List<String> warnings,
    List<AlgorithmHint> algorithmHints,
    NormalizedVisualizationResponse visualization) {

  public DebugStepsResponse(List<ExecutionObservation> steps, List<String> warnings) {
    this(steps, warnings, List.of(), null);
  }

  public DebugStepsResponse(
      List<ExecutionObservation> steps, List<String> warnings, List<AlgorithmHint> algorithmHints) {
    this(steps, warnings, algorithmHints, null);
  }

  public DebugStepsResponse {
    steps = List.copyOf(steps);
    warnings = List.copyOf(warnings);
    algorithmHints = List.copyOf(algorithmHints);
    visualization =
        visualization == null
            ? new VisualizationOutputMapper().map(steps, algorithmHints)
            : visualization;
  }
}
