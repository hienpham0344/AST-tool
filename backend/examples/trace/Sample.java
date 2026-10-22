public class Sample {
    public static void main(String[] args) {
        int[] a = {7, 3};
        int i = 0;
        int j = 1;
        if (a[i] > a[j]) {
            int temp = a[i];
            a[i] = a[j];
            a[j] = temp;
        }
    }
}
