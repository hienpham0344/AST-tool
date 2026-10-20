class Sample {
  void sort(int[] data) {
    int pass = 0;
    while (pass < data.length - 1) {
      int selected = pass;
      int scan = pass + 1;
      while (scan < data.length) {
        if (data[scan] < data[selected]) selected = scan;
        scan++;
      }
      int saved = data[pass];
      data[pass] = data[selected];
      data[selected] = saved;
      pass++;
    }
  }
}
