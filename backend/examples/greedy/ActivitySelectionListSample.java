import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ActivitySelectionListSample {
  record Slot(int start, int end) {}

  static List<Slot> select(List<Slot> intervals) {
    intervals.sort(Comparator.comparingInt(slot -> slot.end()));
    List<Slot> chosen = new ArrayList<>();
    int lastEnd = Integer.MIN_VALUE;
    for (Slot candidate : intervals) {
      if (candidate.start() >= lastEnd) {
        chosen.add(candidate);
        lastEnd = candidate.end();
      }
    }
    return chosen;
  }

  public static void main(String[] args) {
    List<Slot> intervals = new ArrayList<>();
    intervals.add(new Slot(5, 7));
    intervals.add(new Slot(1, 3));
    intervals.add(new Slot(3, 5));
    intervals.add(new Slot(2, 4));
    System.out.println(select(intervals).size());
  }
}
