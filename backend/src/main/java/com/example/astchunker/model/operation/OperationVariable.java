package com.example.astchunker.model.operation;

/** A visible entity. valueStatus distinguishes actual Java null from missing/uninitialized. */
public record OperationVariable(
    String instanceId,
    String declarationId,
    String scopeInstanceId,
    String name,
    String declaredType,
    String valueStatus,
    Object value,
    String objectId) {}
