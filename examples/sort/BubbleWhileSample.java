public class BubbleWhileSample {
  public static void main(String[] args) {
    int[][] cases = {{}, {7}, {1, 2, 3}, {3, 2, 1}, {4, 2, 4, 1, 2}, {0, -3, 2, -1}};
    for (int[] input : cases) {
      sort(input.clone());
    }
  }

  private static void sort(int[] nums) {
    int pass = 0;
    while (pass < nums.length - 1) {
      int scan = 0;
      while (scan < nums.length - 1 - pass) {
        if (nums[scan] > nums[scan + 1]) {
          int saved = nums[scan];
          nums[scan] = nums[scan + 1];
          nums[scan + 1] = saved;
        }
        scan++;
      }
      pass++;
    }
    System.out.println(java.util.Arrays.toString(nums));
  }
}
