public class SlidingWindowSample {
    public static void main(String[] args) {
        int[] nums = {2, 1, 5, 1, 3, 2};
        int k = 3;

        int windowSum = 0;

        for (int i = 0; i < k; i++) {
            windowSum += nums[i];
        }

        int maxSum = windowSum;

        for (int right = k; right < nums.length; right++) {
            int left = right - k;

            windowSum -= nums[left];
            windowSum += nums[right];

            if (windowSum > maxSum) {
                maxSum = windowSum;
            }

            System.out.println(
                "Window [" + (left + 1) + ", " + right + "] = " + windowSum
            );
        }

        System.out.println("Max sum = " + maxSum);
    }
}
