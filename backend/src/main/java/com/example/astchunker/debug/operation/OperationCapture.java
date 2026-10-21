package com.example.astchunker.debug.operation;

import com.example.astchunker.model.ExecutionContext;
import com.example.astchunker.model.ObservationResult;
import java.util.List;

public record OperationCapture(
    long sequence,
    OperationSite site,
    ExecutionContext context,
    List<ObservationResult> variables,
    Boolean conditionOutcome) {
  public OperationCapture {
    variables = List.copyOf(variables);
  }
}
