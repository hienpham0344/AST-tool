package com.example.astchunker.model.operation;

import com.example.astchunker.model.ExecutionContext;
import com.example.astchunker.model.ObservationPoint;
import java.util.List;
import java.util.Map;

public record OperationStep(
    long sequence,
    String operationExecutionId,
    String operationId,
    String phase,
    String kind,
    ObservationPoint source,
    ExecutionContext context,
    String snapshotId,
    String beforeSnapshotId,
    String afterSnapshotId,
    List<OperationVariable> variables,
    Map<String, OperationObject> objects,
    List<OperationEffect> effects,
    String status,
    Boolean conditionOutcome,
    List<String> scopeInstanceIds,
    Map<String, String> attributes) {
  public OperationStep {
    variables = List.copyOf(variables);
    objects = Map.copyOf(objects);
    effects = List.copyOf(effects);
    scopeInstanceIds = List.copyOf(scopeInstanceIds);
    attributes = Map.copyOf(attributes);
  }
}
