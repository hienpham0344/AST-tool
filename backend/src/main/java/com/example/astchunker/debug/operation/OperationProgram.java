package com.example.astchunker.debug.operation;

import java.util.Map;

public record OperationProgram(
    String sourceCode, String helperMethodName, Map<Integer, OperationSite> sites) {
  public OperationProgram {
    sites = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sites));
  }
}
