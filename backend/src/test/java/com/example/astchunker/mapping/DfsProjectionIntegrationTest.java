package com.example.astchunker.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.astchunker.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DfsProjectionIntegrationTest {
  private AlgorithmHint hint(String representation, Map<String, String> ids) {
    var h = mock(AlgorithmHint.class);
    when(h.dfs()).thenReturn(new DfsPattern(1, representation, ids, Map.of(), List.of()));
    return h;
  }

  @Test
  void selectsDfsGraphAndVisitedOnly() {
    var p =
        BfsProjection.fromHints(
            List.of(
                hint(
                    "ADJACENCY_MATRIX",
                    Map.of("graph", "g", "visited", "v", "current", "c", "neighbor", "n"))));
    assertThat(p.roles()).containsOnlyKeys("g", "v");
    assertThat(p.fieldsFor("g")).isEqualTo(new BfsProjection.Fields("graph", "ADJACENCY_MATRIX"));
    assertThat(p.fieldsFor("v")).isEqualTo(new BfsProjection.Fields("visited", ""));
  }

  @Test
  void sharedBfsDfsRolesAreCompatibleAndConflictsStayOmitted() {
    var bfs = mock(AlgorithmHint.class);
    when(bfs.bfs())
        .thenReturn(
            new BfsPattern(
                1,
                "ADJACENCY_MATRIX",
                Map.of("graph", "g", "visited", "v", "queue", "q"),
                Map.of(),
                List.of(),
                0));
    var dfs = hint("ADJACENCY_MATRIX", Map.of("graph", "g", "visited", "v"));
    assertThat(BfsProjection.fromHints(List.of(bfs, dfs)).roles()).containsOnlyKeys("g", "v", "q");
    var conflict = hint("ADJACENCY_LIST", Map.of("graph", "g", "visited", "q"));
    assertThat(BfsProjection.fromHints(List.of(bfs, conflict, dfs)).roles()).containsOnlyKeys("v");
  }
}
