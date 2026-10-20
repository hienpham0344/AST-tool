class Sample {
  void sort(int[] data) {
    for (int pass = 0; pass < data.length - 1; pass++) {
      for (int scan = 0; scan < data.length - 1 - pass; scan++) {
        if (data[scan] > data[scan + 1]) swap(data, scan, scan + 1);
      }
    }
  }

  private void swap(int[] values, int left, int right) {
    int saved = values[left];
    values[left] = values[right];
    values[right] = saved;
  }
}
