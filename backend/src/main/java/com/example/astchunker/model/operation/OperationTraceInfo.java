package com.example.astchunker.model.operation;

import java.util.Map;

public record OperationTraceInfo(
    int schemaVersion,
    String mode,
    String granularity,
    String captureStatus,
    String terminationReason,
    Map<String, Object> limits) {
  public OperationTraceInfo {
    limits = Map.copyOf(limits);
  }
}
