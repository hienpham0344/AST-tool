class Sample {
  void sort(int[] data) {
    int pass = 0;
    while (pass < data.length - 1) {
      int scan = 0;
      while (scan < data.length - 1 - pass) {
        if (data[scan] > data[scan + 1]) {
          int saved = data[scan];
          data[scan] = data[scan + 1];
          data[scan + 1] = saved;
        }
        scan++;
      }
      pass++;
    }
  }
}
