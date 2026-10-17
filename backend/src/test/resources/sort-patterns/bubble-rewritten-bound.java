class Sample {
  void sort(int[] data) {
    for (int pass = 0; pass < data.length - 1; pass++) {
      for (int scan = 0; scan + 1 < data.length - pass; scan++) {
        if (data[scan + 1] < data[scan]) {
          int saved = data[scan];
          data[scan] = data[scan + 1];
          data[scan + 1] = saved;
        }
      }
    }
  }
}
