public class Sample { public static void main(String[] args) {
  int[] a={7,3}; int i=0,j=1;
  { int temp=a[i]; int ordinary=10; a[i]=a[j]; a[j]=temp; }
} }
