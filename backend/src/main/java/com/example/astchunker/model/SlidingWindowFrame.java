package com.example.astchunker.model;

import java.util.List;

/** One observed candidate window; no claim about solution correctness. */
public record SlidingWindowFrame(Long invocationId, Long inputObjectId, String kind,
                                 Long rightIndex, Long leftIndex, Long windowSize,
                                 Integer length, Long start, Long endExclusive,
                                 Object enteringValue, Object leavingValue,
                                 Long accumulator, String effectStatus,
                                 List<String> sourceEvidence, String snapshotPhase) {
  public SlidingWindowFrame { sourceEvidence = List.copyOf(sourceEvidence); }
}
