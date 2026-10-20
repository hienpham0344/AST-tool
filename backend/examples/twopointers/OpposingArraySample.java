public class OpposingArraySample {
  static void reverse(int[] values) {
    int left = 0;
    int right = values.length - 1;
    while (left < right) {
      int saved = values[left];
      values[left] = values[right];
      values[right] = saved;
      left++;
      right--;
    }
  }

  public static void main(String[] args) {
    int[] values = {4, 2, 7, 1};
    reverse(values);
    System.out.println(java.util.Arrays.toString(values));
  }
}
