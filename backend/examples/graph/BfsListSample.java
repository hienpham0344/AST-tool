import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class BfsListSample {
  static void walk(List<List<Integer>> network, int start) {
    boolean[] discovered = new boolean[network.size()];
    Queue<Integer> frontier = new LinkedList<>();
    discovered[start] = true;
    frontier.add(start);
    for (; !frontier.isEmpty(); ) {
      int vertex = frontier.remove();
      for (int candidate : network.get(vertex)) {
        if (!discovered[candidate]) {
          discovered[candidate] = true;
          frontier.add(candidate);
        }
      }
    }
  }

  public static void main(String[] args) {
    List<List<Integer>> network = new ArrayList<>();
    network.add(new ArrayList<>(Arrays.asList(1, 1, 2)));
    network.add(new LinkedList<>(Arrays.asList(3)));
    network.add(new ArrayList<>(Arrays.asList(3)));
    network.add(new ArrayList<>(Arrays.asList(0)));
    network.add(new ArrayList<>(Arrays.asList(4)));
    walk(network, 0);
  }
}
