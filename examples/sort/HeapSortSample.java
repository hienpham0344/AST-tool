import java.util.Arrays;

public class HeapSortSample {
  public static void main(String[] args) {
    int[][] cases = {
        {}, {7}, {3, 1, 2}, {1, 2, 3}, {4, 2, 4, 1, 2}, {-1, 2, -3, 0}
    };
    for (int[] data : cases) {
      sort(data);
      System.out.println(Arrays.toString(data));
    }
  }

  private static void sort(int[] data) {
    int size = data.length;
    for (int start = size / 2 - 1; start >= 0; start--) {
      sift(data, size, start);
    }
    for (int end = size - 1; end > 0; end--) {
      int saved = data[0];
      data[0] = data[end];
      data[end] = saved;
      sift(data, end, 0);
    }
  }

  private static void sift(int[] values, int limit, int cursor) {
    while (2 * cursor + 1 < limit) {
      int left = 2 * cursor + 1;
      int right = left + 1;
      int chosen = left;
      if (right < limit && values[right] > values[left]) chosen = right;
      if (values[cursor] >= values[chosen]) break;
      int saved = values[cursor];
      values[cursor] = values[chosen];
      values[chosen] = saved;
      cursor = chosen;
    }
  }
}
