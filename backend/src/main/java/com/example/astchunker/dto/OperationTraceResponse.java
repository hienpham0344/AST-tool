package com.example.astchunker.dto;

import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.operation.OperationStep;
import com.example.astchunker.model.operation.OperationTraceInfo;
import java.util.List;

public record OperationTraceResponse(
    List<AlgorithmHint> algorithmHints,
    List<OperationStep> steps,
    List<String> warnings,
    OperationTraceInfo traceInfo) {
  public OperationTraceResponse {
    algorithmHints = List.copyOf(algorithmHints);
    steps = List.copyOf(steps);
    warnings = List.copyOf(warnings);
  }
}
