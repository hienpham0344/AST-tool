package com.example.astchunker.visualization.output;

import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.SortFrame;
import com.example.astchunker.model.VisualEvent;
import com.example.astchunker.model.VisualState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stateless projection of enriched execution snapshots onto the visualization v1.0 contract. */
public final class VisualizationOutputMapper {
  public NormalizedVisualizationResponse map(
      List<ExecutionObservation> steps, List<AlgorithmHint> hints) {
    Objects.requireNonNull(steps, "steps");
    Objects.requireNonNull(hints, "hints");
    return new NormalizedVisualizationResponse(
        "1.0", algorithms(hints), frames(steps, hints.size()));
  }

  private List<NormalizedVisualizationResponse.Algorithm> algorithms(List<AlgorithmHint> hints) {
    List<NormalizedVisualizationResponse.Algorithm> result = new ArrayList<>();
    for (int index = 0; index < hints.size(); index++) {
      AlgorithmHint hint = Objects.requireNonNull(hints.get(index), "hint");
      result.add(
          new NormalizedVisualizationResponse.Algorithm(
              "hint-" + index,
              index,
              hint.type(),
              hint.visualPlan(),
              hint.confidence(),
              hint.patternAstNodeId(),
              hint.methodAstNodeId(),
              new NormalizedVisualizationResponse.SourceRange(hint.startLine(), hint.endLine()),
              hint.evidence(),
              hint.variables(),
              hint.variableDeclarationIds(),
              hint.sort()));
    }
    return result;
  }

  private List<NormalizedVisualizationResponse.Frame> frames(
      List<ExecutionObservation> steps, int hintCount) {
    List<NormalizedVisualizationResponse.Frame> result = new ArrayList<>();
    for (ExecutionObservation step : steps) {
      Objects.requireNonNull(step, "step");
      result.add(
          new NormalizedVisualizationResponse.Frame(
              step.sequence(),
              step.snapshotPhase(),
              step.granularity(),
              new NormalizedVisualizationResponse.Source(
                  step.statementAstNodeId(),
                  step.lineNumber(),
                  step.startLine(),
                  step.endLine(),
                  step.startColumn(),
                  step.endColumn()),
              step.context(),
              states(step, hintCount),
              changes(step.visualEvents())));
    }
    return result;
  }

  private List<NormalizedVisualizationResponse.State> states(
      ExecutionObservation step, int hintCount) {
    List<NormalizedVisualizationResponse.State> result = new ArrayList<>();
    for (VisualState state : step.visualStates()) {
      Objects.requireNonNull(state, "visual state");
      SortFrame sort = state.sortFrame();
      result.add(
          new NormalizedVisualizationResponse.State(
              state.id(),
              algorithmId(state.hintIndex(), hintCount),
              state.status(),
              array(state.array()),
              state.pointers(),
              scalars(state),
              state.range(),
              operations(sort, step.sequence()),
              highlights(state, sort),
              sortedRegion(sort),
              state.notes(),
              sort == null ? null : sort.phase(),
              sort == null ? null : sort.snapshotPhase()));
    }
    return result;
  }

  private String algorithmId(int hintIndex, int hintCount) {
    return hintIndex >= 0 && hintIndex < hintCount ? "hint-" + hintIndex : null;
  }

  private VisualState.ArrayValue array(VisualState.ArrayValue source) {
    if (source == null) return null;
    List<Object> values = new ArrayList<>();
    source
        .values()
        .forEach(value -> values.add(NormalizedVisualizationResponse.freezeJsonValue(value)));
    return new VisualState.ArrayValue(
        source.variableName(),
        source.astNodeId(),
        source.status(),
        values,
        source.length(),
        source.truncated());
  }

  private java.util.Map<String, VisualState.Scalar> scalars(VisualState state) {
    java.util.Map<String, VisualState.Scalar> result = new java.util.TreeMap<>();
    state
        .scalars()
        .forEach(
            (role, scalar) ->
                result.put(
                    role,
                    scalar == null
                        ? null
                        : new VisualState.Scalar(
                            scalar.variableName(),
                            scalar.astNodeId(),
                            NormalizedVisualizationResponse.freezeJsonValue(scalar.value()),
                            scalar.status())));
    return result;
  }

  private com.example.astchunker.model.SortFrame.SortedRegion sortedRegion(SortFrame sort) {
    if (sort == null || sort.sortedRegion() == null) return null;
    return (com.example.astchunker.model.SortFrame.SortedRegion)
        NormalizedVisualizationResponse.freezeJsonValue(sort.sortedRegion());
  }

  private List<NormalizedVisualizationResponse.Operation> operations(
      SortFrame sort, long sequence) {
    if (sort == null) return List.of();
    List<NormalizedVisualizationResponse.Operation> result = new ArrayList<>();
    if (sort.comparison() != null) result.add(comparison(sort.comparison(), sort, sequence));
    if (sort.pendingMutation() != null)
      result.add(mutation(sort.pendingMutation(), "pendingMutation", sort, sequence));
    if (sort.completedMutation() != null)
      result.add(mutation(sort.completedMutation(), "completedMutation", sort, sequence));
    return result;
  }

  private NormalizedVisualizationResponse.Operation comparison(
      SortFrame.Comparison comparison, SortFrame sort, long sequence) {
    return new NormalizedVisualizationResponse.Operation(
        "COMPARISON",
        "COMPARISON",
        comparison.firstIndex(),
        comparison.secondIndex(),
        null,
        null,
        comparison.firstValue(),
        comparison.secondValue(),
        null,
        comparison.swapRequired(),
        comparison.action(),
        comparison.actionRequired(),
        "UNKNOWN",
        comparison.status(),
        provenance("comparison", sequence, sort));
  }

  private NormalizedVisualizationResponse.Operation mutation(
      SortFrame.Mutation mutation, String slot, SortFrame sort, long sequence) {
    return new NormalizedVisualizationResponse.Operation(
        "MUTATION",
        mutation.kind(),
        null,
        null,
        mutation.fromIndex(),
        mutation.toIndex(),
        null,
        null,
        mutation.value(),
        null,
        null,
        null,
        lifecycle(mutation.status(), slot),
        mutation.status(),
        provenance(slot, sequence, sort));
  }

  private String lifecycle(String status, String slot) {
    if (slot.equals("pendingMutation") && "PENDING_BEFORE_LOCATION".equals(status))
      return "PENDING";
    if (slot.equals("completedMutation") && "OBSERVED_AFTER_WRITE".equals(status))
      return "OBSERVED";
    return "UNKNOWN";
  }

  private NormalizedVisualizationResponse.Provenance provenance(
      String field, long sequence, SortFrame sort) {
    return new NormalizedVisualizationResponse.Provenance(
        "SortFrame", field, sequence, sort.phase(), sort.snapshotPhase());
  }

  private List<NormalizedVisualizationResponse.Highlight> highlights(
      VisualState state, SortFrame sort) {
    if (!drawableArray(state)) return List.of();
    List<NormalizedVisualizationResponse.Highlight> result = new ArrayList<>();
    state.pointers().entrySet().stream()
        .sorted(java.util.Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              String role = entry.getKey();
              VisualState.Pointer pointer = entry.getValue();
              if (pointer != null
                  && "VALID".equals(pointer.status())
                  && drawable(pointer.index(), state.array())) {
                result.add(
                    new NormalizedVisualizationResponse.Highlight(
                        "POINTER", pointer.index(), role, "pointer"));
              }
            });
    if (sort == null) return result;
    if (sort.comparison() != null) {
      highlight(result, "COMPARISON", "first", "comparison", sort.comparison().firstIndex(), state);
      highlight(
          result, "COMPARISON", "second", "comparison", sort.comparison().secondIndex(), state);
    }
    if (sort.pendingMutation() != null) {
      highlight(
          result, "MUTATION", "from", "pendingMutation", sort.pendingMutation().fromIndex(), state);
      highlight(
          result, "MUTATION", "to", "pendingMutation", sort.pendingMutation().toIndex(), state);
    }
    if (sort.completedMutation() != null) {
      highlight(
          result,
          "MUTATION",
          "from",
          "completedMutation",
          sort.completedMutation().fromIndex(),
          state);
      highlight(
          result, "MUTATION", "to", "completedMutation", sort.completedMutation().toIndex(), state);
    }
    return result;
  }

  private boolean drawableArray(VisualState state) {
    return state.array() != null && "AVAILABLE".equals(state.array().status());
  }

  private void highlight(
      List<NormalizedVisualizationResponse.Highlight> result,
      String kind,
      String role,
      String source,
      Integer index,
      VisualState state) {
    if (drawable(index == null ? null : index.longValue(), state.array())) {
      result.add(new NormalizedVisualizationResponse.Highlight(kind, index, role, source));
    }
  }

  private boolean drawable(Long index, VisualState.ArrayValue array) {
    return index != null
        && array.length() != null
        && index >= 0
        && index < array.length()
        && index < array.values().size();
  }

  private List<NormalizedVisualizationResponse.Change> changes(List<VisualEvent> events) {
    List<NormalizedVisualizationResponse.Change> result = new ArrayList<>();
    for (VisualEvent event : events) {
      Objects.requireNonNull(event, "visual event");
      result.add(
          new NormalizedVisualizationResponse.Change(
              event.stateId(),
              event.type(),
              event.target(),
              event.fromSequence(),
              event.toSequence(),
              event.before(),
              event.after()));
    }
    return result;
  }
}
