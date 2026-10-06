package com.example.astchunker.visualization.sort;

import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionContext;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.ObservationResult;
import com.example.astchunker.model.SortFrame;
import com.example.astchunker.model.SortFrame.Comparison;
import com.example.astchunker.model.SortFrame.Mutation;
import com.example.astchunker.model.SortFrame.SortedRegion;
import com.example.astchunker.model.SortPattern.Direction;
import com.example.astchunker.model.VisualState.ArrayValue;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Creates algorithm-specific sort details from the current and prior observed snapshots. */
public final class SortTraceBuilder {
  public SortFrame build(
      AlgorithmHint hint,
      ExecutionObservation previous,
      ExecutionObservation step,
      ArrayValue previousArray,
      ArrayValue array,
      Function<String, ObservationResult> currentValue,
      Function<String, ObservationResult> previousValue,
      boolean terminalPoint) {
    if (!hint.type().equals("bubble-sort") || hint.sort() == null) return null;
    return bubble(hint, previous, step, previousArray, array, currentValue, previousValue,
        terminalPoint);
  }

  private SortFrame bubble(
      AlgorithmHint hint,
      ExecutionObservation previous,
      ExecutionObservation step,
      ArrayValue previousArray,
      ArrayValue array,
      Function<String, ObservationResult> currentValue,
      Function<String, ObservationResult> previousValue,
      boolean terminalPoint) {
    var pattern = hint.sort();
    String pointId = step.statementAstNodeId();
    Integer index = index(currentValue.apply("scan"));
    Mutation completed = completedSwap(hint, previous, step, previousArray, array, previousValue);
    String phase = terminalPoint ? "SORT_COMPLETED" : completed == null ? "LOOP_PROGRESS" : "SWAP_COMPLETED";
    Comparison comparison = null;
    Mutation pending = null;

    if (pointId.equals(pattern.conditionStatementAstNodeId())) {
      phase = completed == null ? "COMPARISON" : "SWAP_COMPLETED_AND_COMPARISON";
      comparison = comparison(array, index, pattern.direction() == Direction.ASCENDING);
    }

    List<String> swap = pattern.swapStatementAstNodeIds();
    if (index != null && index >= 0 && index + 1 < array.values().size() && swap.size() == 3) {
      if (pointId.equals(swap.get(0))) {
        phase = "SWAP_SAVE_PENDING";
        pending = new Mutation("SAVE_LEFT_VALUE", index, index + 1,
            array.values().get(index), "PENDING_BEFORE_LOCATION");
      } else if (pointId.equals(swap.get(1))) {
        phase = "SWAP_LEFT_WRITE_PENDING";
        pending = new Mutation("WRITE_RIGHT_VALUE_TO_LEFT", index + 1, index,
            array.values().get(index + 1), "PENDING_BEFORE_LOCATION");
      } else if (pointId.equals(swap.get(2))) {
        phase = "SWAP_RIGHT_WRITE_PENDING";
        ObservationResult temp = currentValue.apply("temp");
        pending = new Mutation("WRITE_SAVED_VALUE_TO_RIGHT", index, index + 1,
            temp == null ? null : temp.visualValue(),
            temp == null ? "VALUE_UNAVAILABLE" : "PENDING_BEFORE_LOCATION");
      }
    }

    SortedRegion region = terminalPoint
        ? terminalRegion(array)
        : bubbleSortedRegion(array, currentValue.apply("pass"));
    return new SortFrame(phase, comparison, pending, completed, region, "BEFORE_LOCATION");
  }

  private SortedRegion terminalRegion(ArrayValue array) {
    if (!array.status().equals("AVAILABLE") || array.length() == null)
      return new SortedRegion(0, 0, "UNKNOWN", "ARRAY_UNAVAILABLE");
    if (array.truncated())
      return new SortedRegion(0, array.length(), "TRUNCATED", "NORMAL_LOOP_EXIT");
    return new SortedRegion(0, array.length(), "COMPLETE_AFTER_LOOP", "NORMAL_LOOP_EXIT");
  }

  private Comparison comparison(ArrayValue array, Integer index, boolean ascending) {
    if (index == null || index < 0 || index + 1 >= array.values().size())
      return new Comparison(index, index == null ? null : index + 1, null, null, null,
          "OPERAND_UNAVAILABLE");
    Object first = array.values().get(index);
    Object second = array.values().get(index + 1);
    if (!(first instanceof Number a) || !(second instanceof Number b)
        || !Double.isFinite(a.doubleValue()) || !Double.isFinite(b.doubleValue()))
      return new Comparison(index, index + 1, first, second, null, "NON_NUMERIC_VALUE");
    boolean shouldSwap = ascending ? a.doubleValue() > b.doubleValue()
        : a.doubleValue() < b.doubleValue();
    return new Comparison(index, index + 1, first, second, shouldSwap, "AVAILABLE_FROM_SNAPSHOT");
  }

  private Mutation completedSwap(
      AlgorithmHint hint, ExecutionObservation previous, ExecutionObservation step,
      ArrayValue previousArray, ArrayValue currentArray,
      Function<String, ObservationResult> previousValue) {
    var pattern = hint.sort();
    Integer index = previous == null ? null : index(previousValue.apply("scan"));
    if (previous == null || previous.sequence() + 1 != step.sequence() || index == null
        || pattern.swapStatementAstNodeIds().size() != 3
        || !previous.statementAstNodeId().equals(pattern.swapStatementAstNodeIds().get(2))
        || !sameInvocation(previous.context(), step.context())) return null;
    if (previousArray == null || !previousArray.status().equals("AVAILABLE")
        || !currentArray.status().equals("AVAILABLE") || previousArray.truncated()
        || currentArray.truncated() || index < 0 || index + 1 >= previousArray.values().size()
        || index + 1 >= currentArray.values().size()) return null;
    ObservationResult temp = previousValue.apply("temp");
    Object shiftedValue = previousArray.values().get(index + 1);
    if (temp == null || !Objects.equals(previousArray.values().get(index), shiftedValue)
        || !Objects.equals(currentArray.values().get(index), shiftedValue)
        || !Objects.equals(currentArray.values().get(index + 1), temp.visualValue())) return null;
    return new Mutation("SWAP_COMPLETED", index, index + 1, temp.visualValue(),
        "OBSERVED_AFTER_WRITE");
  }

  private boolean sameInvocation(ExecutionContext first, ExecutionContext second) {
    return first != null && second != null && first.threadId() == second.threadId()
        && first.stackDepth() == second.stackDepth()
        && Objects.equals(first.className(), second.className())
        && Objects.equals(first.methodName(), second.methodName())
        && Objects.equals(first.methodSignature(), second.methodSignature());
  }

  private SortedRegion bubbleSortedRegion(ArrayValue array, ObservationResult pass) {
    Long count = pass == null ? null : integer(pass.visualValue());
    if (count == null || array.length() == null || count < 0 || count > array.length())
      return new SortedRegion(0, 0, "UNKNOWN", "");
    int start = Math.max(0, array.length() - count.intValue());
    return new SortedRegion(start, array.length(), start == array.length() ? "EMPTY" : "INFERRED",
        "CANONICAL_BUBBLE_PASS_COUNT");
  }

  private Integer index(ObservationResult value) {
    Long number = value == null ? null : integer(value.visualValue());
    return number == null ? null : number.intValue();
  }

  private Long integer(Object value) {
    if (value instanceof Byte || value instanceof Short || value instanceof Integer
        || value instanceof Long) {
      long number = ((Number) value).longValue();
      if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) return number;
    }
    return null;
  }
}
