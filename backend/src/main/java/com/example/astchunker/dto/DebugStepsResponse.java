package com.example.astchunker.dto;

import com.example.astchunker.model.ExecutionObservation;
import java.util.List;

/** Additive grouped execution response for the visualizer; the legacy debug response is unchanged. */
public record DebugStepsResponse(
    List<ExecutionObservation> steps, List<String> warnings) {

  public DebugStepsResponse {
    steps = List.copyOf(steps);
    warnings = List.copyOf(warnings);
  }
}
