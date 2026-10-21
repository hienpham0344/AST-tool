public class PrefixSumSameLengthSample {
  public static void main(String[] args) {
    long[] source = {2L, -1L, 3L, 0L};
    long[] cumulative = new long[source.length];
    cumulative[0] = source[0];
    int position = 1;
    while (position < source.length) {
      cumulative[position] = source[position] + cumulative[position - 1];
      position++;
    }
    long done = cumulative[3];
  }
}
