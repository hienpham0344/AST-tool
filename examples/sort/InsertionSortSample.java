public class InsertionSortSample {
  public static void main(String[] args) {
    int[] nums = {5, 1, 4, 2, 2};
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
