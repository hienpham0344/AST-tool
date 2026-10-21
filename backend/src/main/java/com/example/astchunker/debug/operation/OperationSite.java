package com.example.astchunker.debug.operation;

import com.example.astchunker.model.ObservationPoint;
import java.util.List;
import java.util.Map;

public record OperationSite(
    int markerId,
    String operationId,
    String phase,
    String kind,
    ObservationPoint source,
    List<String> visibleDeclarationIds,
    List<String> scopePath,
    Map<String, String> attributes) {
  public OperationSite {
    visibleDeclarationIds = List.copyOf(visibleDeclarationIds);
    scopePath = List.copyOf(scopePath);
    attributes = Map.copyOf(attributes);
  }
}
