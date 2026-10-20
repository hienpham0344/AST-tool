package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import org.junit.jupiter.api.Test;

class BfsDetectorTest {
  private String matrix() {
    return """
    import java.util.*;
    class Sample { void run(int[][] net, int origin) {
      boolean[] seen = new boolean[net.length];
      Queue<Integer> pending = new ArrayDeque<>();
      seen[origin] = true; pending.offer(origin);
      while (!pending.isEmpty()) {
        int vertex = pending.poll();
        for (int next = 0; next < net.length; next++) {
          if (net[vertex][next] != 0 && !seen[next]) { seen[next] = true; pending.offer(next); }
        }
      }
    } }
    """;
  }

  private java.util.List<com.example.astchunker.model.AlgorithmHint> bfs(String s) {
    return new AlgorithmPatternAnalyzer()
        .analyze(new AstAnalyzer().parseCompilationUnit(s)).stream()
            .filter(h -> h.type().equals("bfs"))
            .toList();
  }

  @Test
  void matrixRenamed() {
    assertThat(bfs(matrix())).hasSize(1);
  }

  @Test
  void listAndForAndLinkedList() {
    String s =
        matrix()
            .replace("int[][] net", "List<List<Integer>> net")
            .replace("net.length", "net.size()")
            .replace("new ArrayDeque<>()", "new LinkedList<>()")
            .replace("while (!pending.isEmpty())", "for (; !pending.isEmpty();)")
            .replace(
                "for (int next = 0; next < net.size(); next++)", "for (int next : net.get(vertex))")
            .replace("net[vertex][next] != 0 && ", "");
    assertThat(bfs(s)).hasSize(1);
  }

  @Test
  void literalSource() {
    assertThat(
            bfs(matrix().replace("seen[origin]", "seen[0]").replace("offer(origin)", "offer(0)")))
        .hasSize(1);
  }

  @Test
  void bindsMetadataAndFifoAliases() {
    var hint =
        bfs(matrix()
                .replace("Queue<Integer>", "Deque<Integer>")
                .replace("pending.offer(", "pending.addLast(")
                .replace("pending.poll()", "pending.removeFirst()"))
            .get(0);
    assertThat(hint.bfs().declarationIds())
        .containsKeys("graph", "queue", "visited", "source", "current", "neighbor");
    assertThat(hint.bfs().operationAstNodeIds())
        .containsKeys("seed", "enqueue", "dequeue", "mark", "edge");
    assertThat(hint.bfs().representation()).isEqualTo("ADJACENCY_MATRIX");
    assertThat(hint.bfs().sourceLiteral()).isNull();
    var literal =
        bfs(matrix().replace("seen[origin]", "seen[0]").replace("offer(origin)", "offer(0)"))
            .get(0);
    assertThat(literal.bfs().sourceLiteral()).isZero();
    assertThat(literal.bfs().declarationIds()).doesNotContainKey("source");
  }

  @Test
  void rejectsEscapesAndHiddenMutation() {
    for (String injected :
        java.util.List.of("int[] alias = net[0]; alias[0] = 1;", "change(net[0]);")) {
      assertThat(bfs(matrix().replace("seen[origin] = true;", injected + "seen[origin] = true;")))
          .as(injected)
          .isEmpty();
    }
  }

  @Test
  void rejectsParenthesizedVisitedMutationBeforeSeed() {
    for (String write :
        java.util.List.of("(seen[1]) = true;", "(((seen[1]))) = true;", "((seen[1])) |= true;")) {
      assertThat(bfs(matrix().replace("seen[origin] = true;", write + "seen[origin] = true;")))
          .as(write)
          .isEmpty();
    }
  }

  @Test
  void rejectsStaticImportsShadowingJdkTypes() {
    for (String imported :
        java.util.List.of(
            "other.Types.ArrayDeque",
            "other.Types.Queue",
            "other.Types.Integer",
            "other.Types.*")) {
      String source =
          matrix()
              .replace(
                  "import java.util.*;", "import static " + imported + "; import java.util.*;");
      assertThat(bfs(source)).as(imported).isEmpty();
    }
  }

  @Test
  void rejectsUnsafeVariants() {
    for (String s :
        java.util.List.of(
            matrix().replace("pending.poll()", "pending.pollLast()"),
            matrix().replace("new ArrayDeque<>()", "new PriorityQueue<>()"),
            matrix()
                .replace(
                    "seen[next] = true; pending.offer(next);",
                    "pending.offer(next); seen[next] = true;"),
            matrix().replace("seen[next] = true", "seen[vertex] = true"),
            matrix().replace("pending.offer(next)", "pending.offer(vertex)"),
            matrix().replace("!seen[next]", "!seen[vertex]"),
            matrix().replace("pending.offer(next);", "if (next > 2) pending.offer(next);"),
            matrix()
                .replace(
                    "int vertex = pending.poll();",
                    "pending = new ArrayDeque<>(); int vertex = pending.poll();"),
            matrix().replace("import java.util.*;", "import other.ArrayDeque; import java.util.*;"),
            matrix().replace("class Sample", "class ArrayDeque<T> {} class Sample"))) {
      assertThat(bfs(s)).as(s).isEmpty();
    }
  }
}
