package com.example.astchunker.mapping;

import com.example.astchunker.model.AlgorithmHint;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Declaration-bound selection; conflicting roles are deliberately omitted. */
public record BfsProjection(Map<String, Fields> roles) {
  public BfsProjection {
    roles = Map.copyOf(roles);
  }

  public record Fields(String role, String representation) {}

  public Fields fieldsFor(String declarationId) {
    return roles.get(declarationId);
  }

  public static BfsProjection fromHints(List<AlgorithmHint> hints) {
    Map<String, Fields> roles = new HashMap<>();
    Set<String> conflicts = new HashSet<>();
    for (AlgorithmHint hint : hints) {
      if (hint.bfs() != null)
        select(
            roles,
            conflicts,
            hint.bfs().declarationIds(),
            hint.bfs().representation(),
            List.of("graph", "queue", "visited"));
      if (hint.dfs() != null)
        select(
            roles,
            conflicts,
            hint.dfs().declarationIds(),
            hint.dfs().representation(),
            List.of("graph", "visited"));
    }
    return new BfsProjection(roles);
  }

  private static void select(
      Map<String, Fields> roles,
      Set<String> conflicts,
      Map<String, String> declarations,
      String representation,
      List<String> selected) {
    for (String role : selected) {
      String id = declarations.get(role);
      if (id == null || conflicts.contains(id)) continue;
      Fields fields = new Fields(role, role.equals("graph") ? representation : "");
      Fields previous = roles.putIfAbsent(id, fields);
      if (previous != null && !previous.equals(fields)) {
        roles.remove(id);
        conflicts.add(id);
      }
    }
  }
}
