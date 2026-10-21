public class DfsMatrixSample {
  static void explore(int[][] graph, int vertex, boolean[] visited) {
    int local = vertex + 10;
    visited[vertex] = true;
    for (int neighbor = 0; neighbor < graph.length; neighbor++) {
      if (graph[vertex][neighbor] != 0 && !visited[neighbor]) {
        explore(graph, neighbor, visited);
      }
    }
    int finished = vertex;
  }

  public static void main(String[] args) {
    int[][] graph = {
      {0, 1, 1, 0, 0},
      {0, 0, 1, 0, 0},
      {1, 0, 0, 1, 0},
      {0, 0, 0, 0, 0},
      {0, 0, 0, 0, 0}
    };
    boolean[] visited = new boolean[graph.length];
    int start = 0;
    explore(graph, start, visited);
    int done = 1;
  }
}
