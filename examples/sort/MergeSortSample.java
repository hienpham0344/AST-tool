import java.util.Arrays;

public class MergeSortSample {
  public static void main(String[] args) {
    int[][] inputs = {{}, {7}, {1, 2, 3}, {3, 2, 1}, {4, 2, 4, 1, 2}, {0, -3, 2, -1}};
    for (int[] data : inputs) {
      merge(data, 0, data.length - 1);
      System.out.println(Arrays.toString(data));
    }
  }

  private static void merge(int[] data, int low, int high) {
    if (low >= high) {
      return;
    }
    int mid = low + (high - low) / 2;
    merge(data, low, mid);
    merge(data, mid + 1, high);
    int[] aux = new int[high - low + 1];
    int left = low;
    int right = mid + 1;
    int write = 0;
    while (left <= mid && right <= high) {
      if (data[left] <= data[right]) {
        aux[write] = data[left];
        left++;
      } else {
        aux[write] = data[right];
        right++;
      }
      write++;
    }
    while (left <= mid) {
      aux[write] = data[left];
      left++;
      write++;
    }
    while (right <= high) {
      aux[write] = data[right];
      right++;
      write++;
    }
    for (int index = low; index <= high; index++) {
      data[index] = aux[index - low];
    }
  }
}
