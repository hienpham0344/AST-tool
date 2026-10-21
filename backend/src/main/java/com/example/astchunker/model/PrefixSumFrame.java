package com.example.astchunker.model;

import java.util.List;

/**
 * Observed values plus construction progress proved by the accepted forward scan's control flow.
 * Arrays have AVAILABLE/TRUNCATED/UNKNOWN projections. Recurrence status is PENDING only at its
 * BEFORE_LOCATION source site; NOT_AT_RECURRENCE, UNKNOWN, OUT_OF_BOUNDS and NOT_CAPTURED
 * distinguish other cases. No arithmetic is synthesized. targetValue may still contain a primitive
 * default.
 *
 * <p>confirmedPrefixEndExclusive describes [0,end), not mathematical recomputation. progressStatus
 * is CONFIRMED when those actual cells are captured, TRUNCATED when some values are missing, and
 * UNKNOWN when identity, invocation, bounds or control flow cannot prove progress. An empty
 * confirmed range does not mark the seed complete. seedStatus is PENDING, CONFIRMED or UNKNOWN.
 */
public record PrefixSumFrame(
    int schemaVersion,
    String representation,
    Long invocationId,
    Long sourceArrayObjectId,
    Long resultArrayObjectId,
    VisualState.ArrayValue source,
    VisualState.ArrayValue result,
    Long currentIndex,
    Long sourceIndex,
    Long previousPrefixIndex,
    Long targetIndex,
    Object sourceValue,
    Object previousPrefixValue,
    Object targetValue,
    String recurrenceStatus,
    String seedStatus,
    Long confirmedPrefixEndExclusive,
    List<ConfirmedCell> confirmedCells,
    String progressStatus,
    Query query,
    String snapshotPhase) {
  public PrefixSumFrame {
    confirmedCells = List.copyOf(confirmedCells);
  }

  /** Only captured, integer-valued cells inside the proven constructed prefix appear here. */
  public record ConfirmedCell(long index, Object value, String status) {}

  /** PENDING never contains a computed result; OBSERVED uses the actual next-statement scalar. */
  public record Query(
      String expressionAstNodeId,
      Long left,
      Long right,
      Long upperPrefixIndex,
      Long lowerPrefixIndex,
      Object upperValue,
      Object lowerValue,
      Object result,
      String status) {}
}
