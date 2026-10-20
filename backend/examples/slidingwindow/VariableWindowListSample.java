public class VariableWindowListSample {
  static int longestBelow(java.util.List<Integer> values, int limit) {
    int left = 0;
    int rolling = 0;
    int best = 0;
    for (int right = 0; right < values.size(); right++) {
      rolling += values.get(right);
      while (rolling > limit && left <= right) {
        rolling -= values.get(left);
        left++;
      }
      if (right - left + 1 > best) best = right - left + 1;
    }
    return best;
  }

  public static void main(String[] args) {
    java.util.List<Integer> values = new java.util.ArrayList<>(
        java.util.Arrays.asList(1, 2, 1, 3, 2));
    System.out.println(longestBelow(values, 4));
  }
}
