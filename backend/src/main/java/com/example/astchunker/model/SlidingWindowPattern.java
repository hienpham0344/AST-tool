package com.example.astchunker.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Static evidence for a bounded sum window, without a result-correctness claim. */
public record SlidingWindowPattern(int schemaVersion, String kind, String representation,
                                   Map<String, String> declarationIds,
                                   Map<String, String> operationAstNodeIds,
                                   List<String> evidence) {
  public SlidingWindowPattern {
    if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported window schema");
    if (!List.of("FIXED_SIZE", "VARIABLE_SIZE").contains(kind))
      throw new IllegalArgumentException("Unsupported window kind");
    if (!List.of("INT_ARRAY", "INTEGER_LIST").contains(representation))
      throw new IllegalArgumentException("Unsupported window input");
    declarationIds = Map.copyOf(Objects.requireNonNull(declarationIds));
    operationAstNodeIds = Map.copyOf(Objects.requireNonNull(operationAstNodeIds));
    evidence = List.copyOf(Objects.requireNonNull(evidence));
  }
}
