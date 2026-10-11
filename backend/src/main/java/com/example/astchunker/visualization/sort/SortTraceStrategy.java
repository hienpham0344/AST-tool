package com.example.astchunker.visualization.sort;

import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.ObservationResult;
import com.example.astchunker.model.SortFrame;
import com.example.astchunker.model.VisualState.ArrayValue;
import java.util.function.Function;

interface SortTraceStrategy {
  String algorithm();

  SortFrame build(
      AlgorithmHint hint,
      ExecutionObservation previous,
      ExecutionObservation step,
      ArrayValue previousArray,
      ArrayValue array,
      Function<String, ObservationResult> currentValue,
      Function<String, ObservationResult> previousValue,
      boolean terminalPoint);
}
