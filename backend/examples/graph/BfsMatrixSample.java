import java.util.ArrayDeque;
import java.util.Queue;

public class BfsMatrixSample {
  static void walk(int[][] network, int start) {
    boolean[] discovered = new boolean[network.length];
    Queue<Integer> frontier = new ArrayDeque<>();
    discovered[start] = true;
    frontier.offer(start);
    while (!frontier.isEmpty()) {
      int vertex = frontier.poll();
      for (int candidate = 0; candidate < network.length; candidate++) {
        if (network[vertex][candidate] != 0 && !discovered[candidate]) {
          discovered[candidate] = true;
          frontier.offer(candidate);
        }
      }
    }
  }

  public static void main(String[] args) {
    int[][] network = {
      {0, 1, 1, 0, 0},
      {0, 0, 0, 1, 0},
      {0, 0, 0, 1, 0},
      {1, 0, 0, 0, 0},
      {0, 0, 0, 0, 1}
    };
    walk(network, 0);
  }
}
