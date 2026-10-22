public class ReverseArray {
    public static void main(String[] args) {
        int[] a = {9, 5, 2, 7};
        int left = 0;
        int right = a.length - 1;
        while (left < right) {
            int temp = a[left];
            a[left] = a[right];
            a[right] = temp;
            left++;
            right--;
        }
    }
}
