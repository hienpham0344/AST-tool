package com.example.astchunker.model;

import java.util.List;
import java.util.Map;

/** Observed graph/FIFO state; missing locals and pending operations are never inferred. */
public record BfsFrame(
    Long invocationId,
    Long graphObjectId,
    Long queueObjectId,
    Long visitedObjectId,
    String representation,
    Integer source,
    Integer currentVertex,
    Map<String, Object> graph,
    List<Integer> queue,
    List<Integer> visitedVertices,
    Map<String, Integer> examiningEdge,
    String graphStatus,
    String queueStatus,
    String visitedStatus,
    String snapshotPhase,
    List<String> notes) {
  public BfsFrame {
    graph = Map.copyOf(graph);
    queue = List.copyOf(queue);
    visitedVertices = List.copyOf(visitedVertices);
    examiningEdge = examiningEdge == null ? null : Map.copyOf(examiningEdge);
    notes = List.copyOf(notes);
  }
}
