class Sample {
  void sort(int[] data) {
    for (int pass = 0; pass + 1 < data.length; pass++) {
      int selected = pass;
      for (int scan = pass + 1; scan + 1 <= data.length; scan++) {
        if (data[scan] < data[selected]) selected = scan;
      }
      int temp = data[pass];
      data[pass] = data[selected];
      data[selected] = temp;
    }
  }
}
