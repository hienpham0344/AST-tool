package com.example.astchunker.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Self-contained, variable-derived rendering data at the enclosing step's snapshot phase. */
public record VisualState(
    String id,
    int hintIndex,
    String algorithm,
    String visualPlan,
    String status,
    ArrayValue array,
    Map<String, Pointer> pointers,
    Map<String, Scalar> scalars,
    IndexRange range,
    List<String> notes) {
  public VisualState {
    pointers = Collections.unmodifiableMap(new LinkedHashMap<>(pointers));
    scalars = Collections.unmodifiableMap(new LinkedHashMap<>(scalars));
    notes = List.copyOf(notes);
  }

  public record ArrayValue(
      String variableName,
      String astNodeId,
      String status,
      List<Object> values,
      Integer length,
      boolean truncated) {
    public ArrayValue {
      // A Java reference array may legitimately contain null elements.
      values = Collections.unmodifiableList(new ArrayList<>(values));
    }
  }

  public record Pointer(String variableName, String astNodeId, Long index, String status) {}

  public record Scalar(String variableName, String astNodeId, Object value, String status) {}

  /** [start, endExclusive); a pointer-derived span, never proof of accumulator membership. */
  public record IndexRange(String kind, Long start, Long endExclusive, String status) {}
}
