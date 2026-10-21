public class PrefixSumSentinelSample {
  public static void main(String[] args) {
    int[] data = {2, -1, 3, 0};
    int[] prefix = new int[data.length + 1];
    for (int cursor = 0; cursor < data.length; cursor++) {
      int ordinary = cursor + 10;
      prefix[cursor + 1] = prefix[cursor] + data[cursor];
    }
    int left = 1;
    int right = 3;
    int total = prefix[right + 1] - prefix[left];
    int done = total;
  }
}
