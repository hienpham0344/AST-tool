package com.example.astchunker.model;

import java.util.List;
import java.util.Map;

/** Declaration-bound evidence for an indexed sequential search. */
public record LinearSearchPattern(int schemaVersion, String representation, String resultKind,
                                  Map<String, String> declarationIds,
                                  Map<String, String> operationAstNodeIds,
                                  List<String> evidence) {
  public LinearSearchPattern {
    if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported search schema");
    if (!List.of("INT_ARRAY", "INTEGER_LIST").contains(representation))
      throw new IllegalArgumentException("Unsupported search input");
    if (!List.of("INDEX", "BOOLEAN").contains(resultKind))
      throw new IllegalArgumentException("Unsupported search result");
    declarationIds = Map.copyOf(declarationIds);
    operationAstNodeIds = Map.copyOf(operationAstNodeIds);
    evidence = List.copyOf(evidence);
  }
}
