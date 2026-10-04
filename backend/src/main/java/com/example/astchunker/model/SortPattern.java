package com.example.astchunker.model;

import java.util.List;
import java.util.Objects;

/** Static AST metadata, not evidence that any comparison or swap has executed. */
public record SortPattern(
    int schemaVersion,
    Direction direction,
    String innerLoopAstNodeId,
    String comparisonAstNodeId,
    String conditionStatementAstNodeId,
    List<String> swapStatementAstNodeIds,
    Operand first,
    Operand second) {
  public enum Direction {
    ASCENDING,
    DESCENDING
  }

  /** Index = runtime value of the bound local plus offset. */
  public record Operand(String indexDeclarationId, int offset) {
    public Operand {
      requireId(indexDeclarationId);
    }
  }

  public SortPattern {
    if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported sort schema version");
    Objects.requireNonNull(direction, "direction");
    requireId(innerLoopAstNodeId);
    requireId(comparisonAstNodeId);
    requireId(conditionStatementAstNodeId);
    swapStatementAstNodeIds = List.copyOf(swapStatementAstNodeIds);
    if (swapStatementAstNodeIds.size() != 3
        || swapStatementAstNodeIds.stream().distinct().count() != 3) {
      throw new IllegalArgumentException("A temporary swap requires three distinct statements");
    }
    swapStatementAstNodeIds.forEach(SortPattern::requireId);
    Objects.requireNonNull(first, "first");
    Objects.requireNonNull(second, "second");
  }

  private static void requireId(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("AST ID required");
  }
}
