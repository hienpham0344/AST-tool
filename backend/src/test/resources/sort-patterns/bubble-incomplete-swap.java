class Sample {
  void sort(int[] data) {
    for (int pass = 0; pass < data.length - 1; pass++) {
      for (int scan = 0; scan < data.length - 1 - pass; scan++) {
        if (data[scan] > data[scan + 1]) {
          int temp = data[scan];
          data[scan] = data[scan + 1];
          data[scan + 1] = data[scan];
        }
      }
    }
  }
}
