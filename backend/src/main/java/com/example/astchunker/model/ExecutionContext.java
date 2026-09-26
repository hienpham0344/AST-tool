package com.example.astchunker.model;

/** Identifies the suspended frame and bytecode location within one debug run. */
public record ExecutionContext(
    long threadId,
    String threadName,
    String className,
    String methodName,
    String methodSignature,
    int stackDepth,
    long codeIndex) {}
