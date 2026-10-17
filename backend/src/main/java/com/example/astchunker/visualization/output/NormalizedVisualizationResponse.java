package com.example.astchunker.visualization.output;

import com.example.astchunker.model.ExecutionContext;
import com.example.astchunker.model.SortFrame.SortedRegion;
import com.example.astchunker.model.SortPattern;
import com.example.astchunker.model.VisualState.ArrayValue;
import com.example.astchunker.model.VisualState.IndexRange;
import com.example.astchunker.model.VisualState.Pointer;
import com.example.astchunker.model.VisualState.Scalar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Versioned, immutable frontend contract derived from existing visualization snapshots. */
public record NormalizedVisualizationResponse(
    String schemaVersion, List<Algorithm> algorithms, List<Frame> frames) {
  public NormalizedVisualizationResponse {
    algorithms = freezeList(algorithms);
    frames = freezeList(frames);
  }

  public record Algorithm(
      String id,
      int hintIndex,
      String type,
      String visualPlan,
      double confidence,
      String patternAstNodeId,
      String methodAstNodeId,
      SourceRange sourceRange,
      List<String> evidence,
      Map<String, String> variables,
      Map<String, String> variableDeclarationIds,
      SortPattern sort) {
    public Algorithm {
      evidence = freezeList(evidence);
      variables = freezeMap(variables);
      variableDeclarationIds = freezeMap(variableDeclarationIds);
    }
  }

  public record SourceRange(int startLine, int endLine) {}

  public record Frame(
      long sequence,
      String snapshotPhase,
      String granularity,
      Source source,
      ExecutionContext context,
      List<State> states,
      List<Change> changes) {
    public Frame {
      states = freezeList(states);
      changes = freezeList(changes);
    }
  }

  public record Source(
      String astNodeId,
      int lineNumber,
      int startLine,
      int endLine,
      int startColumn,
      int endColumn) {}

  public record State(
      String id,
      String algorithmId,
      String status,
      ArrayValue array,
      Map<String, Pointer> pointers,
      Map<String, Scalar> scalars,
      IndexRange range,
      List<Operation> operations,
      List<Highlight> highlights,
      SortedRegion sortedRegion,
      List<String> notes,
      String phase,
      String snapshotPhase) {
    public State {
      pointers = freezeMap(pointers);
      scalars = freezeMap(scalars);
      operations = freezeList(operations);
      highlights = freezeList(highlights);
      notes = freezeList(notes);
    }
  }

  public record Operation(
      String type,
      String kind,
      Integer firstIndex,
      Integer secondIndex,
      Integer fromIndex,
      Integer toIndex,
      Object firstValue,
      Object secondValue,
      Object value,
      Boolean swapRequired,
      String action,
      Boolean actionRequired,
      String lifecycle,
      String sourceStatus,
      Provenance provenance) {
    public Operation {
      firstValue = freezeJsonValue(firstValue);
      secondValue = freezeJsonValue(secondValue);
      value = freezeJsonValue(value);
    }
  }

  public record Provenance(
      String source, String field, long sequence, String phase, String snapshotPhase) {}

  /** A drawable current snapshot cell; source/role identify why it was highlighted. */
  public record Highlight(String kind, long index, String role, String source) {}

  public record Change(
      String stateId,
      String type,
      String target,
      Long fromSequence,
      long toSequence,
      Object before,
      Object after) {
    public Change {
      before = freezeJsonValue(before);
      after = freezeJsonValue(after);
    }
  }

  private static <T> List<T> freezeList(List<? extends T> source) {
    return Collections.unmodifiableList(new ArrayList<>(source));
  }

  private static <V> Map<String, V> freezeMap(Map<String, ? extends V> source) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(source)));
  }

  static Object freezeJsonValue(Object value) {
    if (value instanceof List<?> values) {
      List<Object> copy = new ArrayList<>();
      values.forEach(item -> copy.add(freezeJsonValue(item)));
      return Collections.unmodifiableList(copy);
    }
    if (value instanceof Map<?, ?> values) {
      Map<Object, Object> copy = new LinkedHashMap<>();
      List<Map.Entry<?, ?>> entries = new ArrayList<>(values.entrySet());
      entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
      entries.forEach(entry -> copy.put(entry.getKey(), freezeJsonValue(entry.getValue())));
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof ArrayValue array) {
      List<Object> copy = new ArrayList<>();
      array.values().forEach(item -> copy.add(freezeJsonValue(item)));
      return new ArrayValue(
          array.variableName(),
          array.astNodeId(),
          array.status(),
          copy,
          array.length(),
          array.truncated());
    }
    if (value instanceof Scalar scalar) {
      return new Scalar(
          scalar.variableName(),
          scalar.astNodeId(),
          freezeJsonValue(scalar.value()),
          scalar.status());
    }
    if (value instanceof SortedRegion region) {
      return new SortedRegion(
          region.start(),
          region.endExclusive(),
          region.status(),
          region.basis(),
          region.violationIndex(),
          freezeJsonValue(region.previousValue()),
          freezeJsonValue(region.currentValue()));
    }
    return value;
  }
}
