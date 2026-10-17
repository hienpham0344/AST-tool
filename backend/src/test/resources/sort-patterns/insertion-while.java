class Sample {
  void sort(int[] data) {
    int pass = 1;
    while (pass < data.length) {
      int key = data[pass];
      int scan = pass - 1;
      while (scan >= 0 && data[scan] > key) {
        data[scan + 1] = data[scan];
        scan--;
      }
      data[scan + 1] = key;
      pass++;
    }
  }
}
