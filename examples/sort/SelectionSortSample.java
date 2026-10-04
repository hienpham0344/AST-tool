public class SelectionSortSample {
  public static void main(String[] args) {
    int[] nums = {5, 1, 4, 2, 2};
    for (int i = 0; i < nums.length - 1; i++) {
      int min = i;
      for (int j = i + 1; j < nums.length; j++) {
        if (nums[j] < nums[min]) min = j;
      }
      int temp = nums[i];
      nums[i] = nums[min];
      nums[min] = temp;
    }
    System.out.println(java.util.Arrays.toString(nums));
  }
}
