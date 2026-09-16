public class ScopedVariablesSample {

  public static void main(String[] args) {
    int outer = 3;

    if (args.length == 0) {
      int value = outer + 7;
      System.out.println("first value = " + value);
    } else {
      int value = outer + 17;
      System.out.println("second value = " + value);
    }
  }
}
