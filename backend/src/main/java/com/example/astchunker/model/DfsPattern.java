package com.example.astchunker.model;

import java.util.List;
import java.util.Map;

/** Declaration-bound proof of direct recursive depth-first graph traversal. */
public record DfsPattern(
    int schemaVersion,
    String representation,
    Map<String, String> declarationIds,
    Map<String, String> operationAstNodeIds,
    List<String> evidence) {
  public DfsPattern {
    if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported DFS schema");
    if (!List.of("ADJACENCY_MATRIX", "ADJACENCY_LIST").contains(representation))
      throw new IllegalArgumentException("Unsupported DFS representation");
    declarationIds = Map.copyOf(declarationIds);
    operationAstNodeIds = Map.copyOf(operationAstNodeIds);
    evidence = List.copyOf(evidence);
  }
}
