public class InsertionWhileSample {
  public static void main(String[] args) {
    int[][] cases = {{}, {7}, {1, 2, 3}, {3, 2, 1}, {4, 2, 4, 1, 2}, {0, -3, 2, -1}};
    for (int[] input : cases) {
      sort(input.clone());
    }
  }

  private static void sort(int[] nums) {
    int pass = 1;
    while (pass < nums.length) {
      int key = nums[pass];
      int scan = pass - 1;
      while (scan >= 0 && nums[scan] > key) {
        nums[scan + 1] = nums[scan];
        scan--;
      }
      nums[scan + 1] = key;
      pass++;
    }
    System.out.println(java.util.Arrays.toString(nums));
  }
}
