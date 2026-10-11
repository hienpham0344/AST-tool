public class InsertionSortSample {
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
    for (int i = 1; i < nums.length; i++) {
      int key = nums[i];
      int j = i - 1;
      while (j >= 0 && nums[j] > key) {
        nums[j + 1] = nums[j];
        j--;
      }
      nums[j + 1] = key;
    }
    System.out.println(java.util.Arrays.toString(nums));
  }
}
