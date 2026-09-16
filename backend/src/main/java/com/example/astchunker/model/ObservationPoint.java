package com.example.astchunker.model;

/** A source-level statement that can be observed through a JDI breakpoint. */
public record ObservationPoint(
    String astNodeId,
    String className,
    int lineNumber,
    int startLine,
    int endLine,
    String statementKind) {}
