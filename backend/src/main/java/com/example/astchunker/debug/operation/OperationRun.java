package com.example.astchunker.debug.operation;

import java.util.List;
import java.util.Map;

public record OperationRun(
    List<OperationCapture> captures,
    List<String> warnings,
    String captureStatus,
    String terminationReason,
    Map<String, Object> limits) {
  public OperationRun {
    captures = List.copyOf(captures);
    warnings = List.copyOf(warnings);
    limits = Map.copyOf(limits);
  }
}
