public class SelectionWhileSample {
  public static void main(String[] args) {
    int[][] cases = {{}, {7}, {1, 2, 3}, {3, 2, 1}, {4, 2, 4, 1, 2}, {0, -3, 2, -1}};
    for (int[] input : cases) {
      sort(input.clone());
    }
  }

  private static void sort(int[] nums) {
    int pass = 0;
    while (pass < nums.length - 1) {
      int selected = pass;
      int scan = pass + 1;
      while (scan < nums.length) {
        if (nums[scan] < nums[selected]) {
          selected = scan;
        }
        scan++;
      }
      int saved = nums[pass];
      nums[pass] = nums[selected];
      nums[selected] = saved;
      pass++;
    }
    System.out.println(java.util.Arrays.toString(nums));
  }
}
