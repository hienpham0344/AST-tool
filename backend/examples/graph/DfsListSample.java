import java.util.ArrayList;
import java.util.List;

public class DfsListSample {
  static void explore(List<List<Integer>> graph, int vertex, boolean[] visited) {
    int local = vertex + 10;
    visited[vertex] = true;
    for (int neighbor : graph.get(vertex)) {
      if (!visited[neighbor]) {
        explore(graph, neighbor, visited);
      }
    }
    int finished = vertex;
  }

  public static void main(String[] args) {
    List<List<Integer>> graph = new ArrayList<>();
    for (int vertex = 0; vertex < 5; vertex++) {
      graph.add(new ArrayList<>());
    }
    graph.get(0).add(1);
    graph.get(0).add(2);
    graph.get(1).add(2);
    graph.get(2).add(0);
    graph.get(2).add(3);
    boolean[] visited = new boolean[graph.size()];
    int start = 0;
    explore(graph, start, visited);
    int done = 1;
  }
}
