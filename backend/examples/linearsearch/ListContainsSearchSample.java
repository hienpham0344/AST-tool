public class ListContainsSearchSample {
  static boolean contains(java.util.List<Integer> values, int target) {
    int scan = 0;
    while (scan < values.size()) {
      if (target == values.get(scan)) return true;
      scan++;
    }
    return false;
  }

  public static void main(String[] args) {
    java.util.List<Integer> values = new java.util.ArrayList<>(
        java.util.Arrays.asList(4, 8, 2, 7));
    System.out.println(contains(values, 2));
  }
}
