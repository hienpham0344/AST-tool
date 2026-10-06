public class SelectionSortSample {
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
      int min = i;
      for (int j = i + 1; j < nums.length; j++) {
        if (nums[j] < nums[min]) {
          min = j;
        }
      }
      int temp = nums[i];
      nums[i] = nums[min];
      nums[min] = temp;
    }
    System.out.println(java.util.Arrays.toString(nums));
  }
}
