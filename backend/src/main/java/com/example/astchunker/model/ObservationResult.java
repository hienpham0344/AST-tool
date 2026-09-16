package com.example.astchunker.model;

/** The runtime value of one source variable at one observation point. */
public record ObservationResult(
    String astNodeId,
    String variableName,
    String declaredType,
    String runtimeValue,
    int lineNumber) {}
