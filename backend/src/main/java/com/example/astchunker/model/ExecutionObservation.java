package com.example.astchunker.model;

import java.util.List;

/** One suspended JDI breakpoint hit and the visible locals captured at that moment. */
public record ExecutionObservation(
    long sequence,
    String statementAstNodeId,
    int lineNumber,
    int startLine,
    int endLine,
    int startColumn,
    int endColumn,
    String statementKind,
    String code,
    List<ObservationResult> variables) {

  public ExecutionObservation {
    if (sequence < 1) {
      throw new IllegalArgumentException("Execution sequence must be positive.");
    }
    variables = List.copyOf(variables);
  }
}
