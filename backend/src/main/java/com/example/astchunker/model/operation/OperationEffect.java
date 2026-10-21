package com.example.astchunker.model.operation;

import java.util.Map;

/** CREATED reserves a separate visual entity before its initialization animation. */
public record OperationEffect(
    String type,
    String entityId,
    Object before,
    Object after,
    Map<String, Object> source,
    Map<String, Object> target,
    String status) {
  public OperationEffect {
    source = Map.copyOf(source);
    target = Map.copyOf(target);
  }
}
