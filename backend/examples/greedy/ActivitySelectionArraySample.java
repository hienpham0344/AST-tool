import java.util.Arrays;

public class ActivitySelectionArraySample {
  static int select(int[][] slots) {
    Arrays.sort(slots, (left, right) -> Integer.compare(left[1], right[1]));
    int lastEnd = Integer.MIN_VALUE;
    int count = 0;
    for (int cursor = 0; cursor < slots.length; cursor++) {
      int[] candidate = slots[cursor];
      if (candidate[0] >= lastEnd) {
        count++;
        lastEnd = candidate[1];
      }
    }
    return count;
  }

  public static void main(String[] args) {
    int[][] slots = {{5, 7}, {1, 3}, {3, 5}, {2, 4}};
    System.out.println(select(slots));
  }
}
