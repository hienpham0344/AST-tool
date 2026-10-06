public class BubbleSortSample {
  public static void main(String[] args) {
    int[][] cases = {
      {},
      {7},
      {1, 2, 3},
      {3, 2, 1},
      {4, 2, 4, 1, 2},
      {0, -3, 2, -1}
    };
    for (int[] input : cases) {
      int[] nums = input.clone();
      sort(nums);
    }
  }

  private static void sort(int[] nums) {
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
