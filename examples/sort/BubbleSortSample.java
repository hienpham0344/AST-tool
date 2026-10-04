public class BubbleSortSample {
  public static void main(String[] args) {
    int[] nums = {5, 1, 4, 2, 2};
    for (int i = 0; i < nums.length - 1; i++) {
      for (int j = 0; j < nums.length - 1 - i; j++) {
        if (nums[j] > nums[j + 1]) {
          int temp = nums[j];
          nums[j] = nums[j + 1];
          nums[j + 1] = temp;
        }
      }
    }
    System.out.println(java.util.Arrays.toString(nums));
  }
}
