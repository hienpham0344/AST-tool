package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.model.SortPattern.Direction;
import com.example.astchunker.model.VisualState.ArrayValue;
import java.util.List;
import org.junit.jupiter.api.Test;

class MergeTraceBuilderRangeTest {
  @Test
  void distinguishesUnavailableEvidenceFromObservedDisorder() {
    assertThat(
            MergeTraceBuilder.rangeStatus(
                new ArrayValue("data", "id", "UNAVAILABLE", List.of(), null, false),
                0,
                2,
                Direction.ASCENDING))
        .isEqualTo("UNKNOWN");
    assertThat(
            MergeTraceBuilder.rangeStatus(
                new ArrayValue("data", "id", "AVAILABLE", List.of(1, 2), 3, true),
                0,
                2,
                Direction.ASCENDING))
        .isEqualTo("UNKNOWN");
    assertThat(
            MergeTraceBuilder.rangeStatus(
                new ArrayValue("data", "id", "AVAILABLE", List.of(1, 2), 2, false),
                0,
                2,
                Direction.ASCENDING))
        .isEqualTo("UNKNOWN");
    assertThat(
            MergeTraceBuilder.rangeStatus(
                new ArrayValue("data", "id", "AVAILABLE", List.of(2, 1), 2, false),
                0,
                1,
                Direction.ASCENDING))
        .isEqualTo("NOT_SORTED");
    assertThat(
            MergeTraceBuilder.rangeStatus(
                new ArrayValue("data", "id", "AVAILABLE", List.of(1, 2), 2, false),
                0,
                1,
                Direction.ASCENDING))
        .isEqualTo("VERIFIED_SORTED");
  }
}
