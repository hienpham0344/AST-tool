package com.example.astchunker.model;

import java.util.List;

/** Candidate comparison at a pre-location breakpoint, not a branch outcome. */
public record LinearSearchFrame(Long invocationId, Long inputObjectId, Long index,
                                Integer length, Object currentValue, Long targetValue,
                                String comparisonStatus, List<String> sourceEvidence,
                                String snapshotPhase) {
  public LinearSearchFrame { sourceEvidence = List.copyOf(sourceEvidence); }
}
