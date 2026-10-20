import java.util.Arrays;

public class QuickSortSample {
  public static void main(String[] args) {
    int[][] inputs = {{}, {7}, {1, 2, 3}, {3, 2, 1}, {4, 2, 4, 1, 2}, {0, -3, 2, -1}};
    for (int[] data : inputs) {
      quick(data, 0, data.length - 1);
      System.out.println(Arrays.toString(data));
    }
  }

  private static void quick(int[] data, int low, int high) {
    if (low >= high) {
      return;
    }
    int pivot = data[high];
    int store = low;
    for (int scan = low; scan < high; scan++) {
      if (data[scan] <= pivot) {
        int saved = data[store];
        data[store] = data[scan];
        data[scan] = saved;
        store++;
      }
    }
    int finalSaved = data[store];
    data[store] = data[high];
    data[high] = finalSaved;
    quick(data, low, store - 1);
    quick(data, store + 1, high);
  }
}
