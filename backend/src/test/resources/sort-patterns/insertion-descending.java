class Sample {
  void sort(int[] data) {
    for (int pass = 1; pass < data.length; pass++) {
      int key = data[pass];
      int scan = pass - 1;
      while (scan >= 0 && data[scan] < key) {
        data[scan + 1] = data[scan];
        scan--;
      }
      data[scan + 1] = key;
    }
  }
}
