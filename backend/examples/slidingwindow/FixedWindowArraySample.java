public class FixedWindowArraySample {
  static int[] sums(int[] values, int width) {
    if (width <= 0 || width > values.length) return new int[0];
    int[] result = new int[values.length - width + 1];
    int rolling = 0;
    for (int cursor = 0; cursor < values.length; cursor++) {
      rolling += values[cursor];
      if (cursor >= width) rolling -= values[cursor - width];
      if (cursor >= width - 1) result[cursor - width + 1] = rolling;
    }
    return result;
  }

  public static void main(String[] args) {
    int[] values = {2, 1, 4, 3, 5};
    System.out.println(java.util.Arrays.toString(sums(values, 3)));
  }
}
