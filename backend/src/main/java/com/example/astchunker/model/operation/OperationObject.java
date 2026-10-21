package com.example.astchunker.model.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record OperationObject(
    String objectId,
    String type,
    List<Object> values,
    Integer length,
    String status,
    boolean truncated,
    long observedAtSequence) {
  public OperationObject {
    values = Collections.unmodifiableList(new ArrayList<>(values));
  }
}
