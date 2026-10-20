public class SameDirectionListSample {
  static int compact(java.util.List<Integer> values) {
    int slow = 0;
    for (int scan = 1; scan < values.size(); scan++) {
      if (!values.get(scan).equals(values.get(slow))) {
        slow++;
        values.set(slow, values.get(scan));
      }
    }
    return values.isEmpty() ? 0 : slow + 1;
  }

  public static void main(String[] args) {
    java.util.List<Integer> values = new java.util.ArrayList<>(
        java.util.Arrays.asList(1, 1, 2, 2, 4));
    int size = compact(values);
    System.out.println(values.subList(0, size));
  }
}
