package com.example.astchunker.model;

/** Runtime-derived sorting details at a single BEFORE_LOCATION snapshot. */
public record SortFrame(
    String phase,
    Comparison comparison,
    Mutation pendingMutation,
    Mutation completedMutation,
    SortedRegion sortedRegion,
    String snapshotPhase) {

  public record Comparison(
      Integer firstIndex,
      Integer secondIndex,
      Object firstValue,
      Object secondValue,
      Boolean swapRequired,
      String status) {}

  /** The operation is pending until a later snapshot confirms the write occurred. */
  public record Mutation(
      String kind,
      Integer fromIndex,
      Integer toIndex,
      Object value,
      String status) {}

  public record SortedRegion(int start, int endExclusive, String status, String basis) {}
}
