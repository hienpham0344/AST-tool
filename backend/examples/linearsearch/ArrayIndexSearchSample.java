public class ArrayIndexSearchSample {
  static int find(int[] numbers, int wanted) {
    for (int cursor = 0; cursor < numbers.length; cursor++) {
      if (numbers[cursor] == wanted) return cursor;
    }
    return -1;
  }

  public static void main(String[] args) {
    int[] numbers = {4, 8, 2, 7};
    System.out.println(find(numbers, 2));
  }
}
