package com.example.astchunker.model;

import java.util.List;
import java.util.Map;

/** Declaration-bound BFS proof; a literal source has no declaration ID. */
public record BfsPattern(
    int schemaVersion,
    String representation,
    Map<String, String> declarationIds,
    Map<String, String> operationAstNodeIds,
    List<String> evidence,
    Integer sourceLiteral) {
  public BfsPattern {
    if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported BFS schema");
    if (!List.of("ADJACENCY_MATRIX", "ADJACENCY_LIST").contains(representation))
      throw new IllegalArgumentException("Unsupported BFS representation");
    declarationIds = Map.copyOf(declarationIds);
    operationAstNodeIds = Map.copyOf(operationAstNodeIds);
    evidence = List.copyOf(evidence);
  }
}
