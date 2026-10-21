package com.example.astchunker.model;

import java.util.List;
import java.util.Map;

/** One observed recursive invocation; visitedVertices is a set in vertex order, not a path. */
public record DfsFrame(
    int schemaVersion,
    Long invocationId,
    int stackDepth,
    Long graphObjectId,
    Long visitedObjectId,
    String representation,
    Integer currentVertex,
    Integer neighborVertex,
    Map<String, Object> graph,
    List<Integer> visitedVertices,
    Map<String, Integer> examiningEdge,
    String graphStatus,
    String visitedStatus,
    String currentStatus,
    String neighborStatus,
    String edgeStatus,
    String markStatus,
    String callStatus,
    String snapshotPhase,
    List<String> notes) {
  public DfsFrame {
    if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported DFS frame schema");
    graph = Map.copyOf(graph);
    visitedVertices = List.copyOf(visitedVertices);
    examiningEdge = examiningEdge == null ? null : Map.copyOf(examiningEdge);
    notes = List.copyOf(notes);
  }
}
