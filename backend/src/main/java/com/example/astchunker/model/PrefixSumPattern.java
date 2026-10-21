package com.example.astchunker.model;

import java.util.List;
import java.util.Map;

/** Declaration-bound proof of a separate-array prefix construction. */
public record PrefixSumPattern(
    int schemaVersion,
    String representation,
    Map<String, String> declarationIds,
    Map<String, String> operationAstNodeIds,
    List<Query> queries,
    List<String> evidence) {
  public PrefixSumPattern {
    if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported prefix schema");
    if (!List.of("SAME_LENGTH", "ZERO_SENTINEL").contains(representation))
      throw new IllegalArgumentException("Unsupported prefix representation");
    declarationIds = Map.copyOf(declarationIds);
    operationAstNodeIds = Map.copyOf(operationAstNodeIds);
    queries = List.copyOf(queries);
    evidence = List.copyOf(evidence);
  }

  /** p[right + 1] - p[left], with both endpoint names bound to integer declarations. */
  public record Query(
      String expressionAstNodeId,
      String leftDeclarationId,
      String rightDeclarationId,
      String leftExpressionAstNodeId,
      String rightExpressionAstNodeId) {}
}
