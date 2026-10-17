package com.example.astchunker.visualization.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.ExecutionContext;
import com.example.astchunker.model.ExecutionObservation;
import com.example.astchunker.model.SortFrame;
import com.example.astchunker.model.SortPattern;
import com.example.astchunker.model.VisualEvent;
import com.example.astchunker.model.VisualState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VisualizationOutputMapperTest {
  private final VisualizationOutputMapper mapper = new VisualizationOutputMapper();

  @Test
  void mapsHintMetadataFramesSourcesStatesAndEventsWithoutJoiningByVariableName() {
    // Break protected: mapping a state by its variable name would incorrectly assign hint-0.
    var hints =
        List.of(
            hintWithSort("binary-search", "array-search", Map.of("array", "numbers")),
            hint("two-pointers", "array-pointers", Map.of("array", "numbers")));
    var secondState =
        state(
            "state-2",
            1,
            "two-pointers",
            array("numbers", "array-id", "AVAILABLE", List.of(4, 5), 2, false),
            Map.of("left", new VisualState.Pointer("left", "left-id", 0L, "VALID")),
            null);
    var invalidState =
        state(
            "state-invalid",
            99,
            "two-pointers",
            array("numbers", "array-id", "AVAILABLE", List.of(4, 5), 2, false),
            Map.of(),
            null);
    var first = observation(7, List.of(), List.of());
    var second =
        observation(
            8,
            List.of(secondState, invalidState),
            List.of(new VisualEvent("state-2", "STATE_ENTERED", "state", null, 8, null, null)));

    var result = mapper.map(List.of(first, second), hints);

    assertThat(result.schemaVersion()).isEqualTo("1.0");
    assertThat(result.algorithms())
        .extracting(NormalizedVisualizationResponse.Algorithm::id)
        .containsExactly("hint-0", "hint-1");
    assertThat(result.algorithms().get(1))
        .extracting(
            NormalizedVisualizationResponse.Algorithm::hintIndex,
            NormalizedVisualizationResponse.Algorithm::type,
            NormalizedVisualizationResponse.Algorithm::visualPlan)
        .containsExactly(1, "two-pointers", "array-pointers");
    assertThat(result.algorithms().get(0))
        .extracting(
            NormalizedVisualizationResponse.Algorithm::confidence,
            NormalizedVisualizationResponse.Algorithm::patternAstNodeId,
            NormalizedVisualizationResponse.Algorithm::methodAstNodeId,
            NormalizedVisualizationResponse.Algorithm::sourceRange,
            NormalizedVisualizationResponse.Algorithm::evidence,
            NormalizedVisualizationResponse.Algorithm::variables,
            NormalizedVisualizationResponse.Algorithm::variableDeclarationIds)
        .containsExactly(
            0.8,
            "pattern-id",
            "method-id",
            new NormalizedVisualizationResponse.SourceRange(3, 9),
            List.of("evidence"),
            Map.of("array", "numbers"),
            Map.of("array", "array-declaration"));
    assertThat(result.algorithms().get(0).sort())
        .isEqualTo(
            new SortPattern(
                1,
                SortPattern.Direction.ASCENDING,
                "inner-loop",
                "comparison",
                "condition",
                List.of(),
                new SortPattern.Operand("first", 0),
                new SortPattern.Operand("second", 1)));
    assertThat(result.frames())
        .extracting(NormalizedVisualizationResponse.Frame::sequence)
        .containsExactly(7L, 8L);
    assertThat(result.frames().get(0).states()).isEmpty();
    assertThat(result.frames().get(1).source())
        .isEqualTo(new NormalizedVisualizationResponse.Source("node-8", 8, 8, 8, 2, 15));
    assertThat(result.frames().get(1))
        .extracting(
            NormalizedVisualizationResponse.Frame::snapshotPhase,
            NormalizedVisualizationResponse.Frame::granularity,
            NormalizedVisualizationResponse.Frame::context)
        .containsExactly(
            "BEFORE_LOCATION",
            "LINE_BREAKPOINT",
            new ExecutionContext(1, "main", "Sample", "main", "()V", 1, 8));
    assertThat(result.frames().get(1).states())
        .extracting(NormalizedVisualizationResponse.State::algorithmId)
        .containsExactly("hint-1", null);
    assertThat(result.frames().get(1).changes())
        .containsExactly(
            new NormalizedVisualizationResponse.Change(
                "state-2", "STATE_ENTERED", "state", null, 8, null, null));
  }

  @Test
  void preservesAllSortSlotsAndDrawsOnlyCapturedValidHighlights() {
    // Break protected: flattening SortFrame into one operation loses simultaneous
    // comparison/mutations.
    List<Object> capturedValues = new ArrayList<>(Arrays.asList(9, null, 7));
    var pointers = new LinkedHashMap<String, VisualState.Pointer>();
    pointers.put("left", new VisualState.Pointer("left", "left-id", 1L, "VALID"));
    pointers.put("tail", new VisualState.Pointer("tail", "tail-id", 4L, "NOT_CAPTURED"));
    pointers.put("outside", new VisualState.Pointer("outside", "outside-id", -1L, "OUT_OF_BOUNDS"));
    var sort =
        new SortFrame(
            "INNER_COMPARE",
            new SortFrame.Comparison(0, 4, 9, null, true, "AVAILABLE_FROM_SNAPSHOT", "SWAP", true),
            new SortFrame.Mutation("SHIFT_RIGHT", 1, 2, 7, "PENDING_BEFORE_LOCATION"),
            new SortFrame.Mutation("INSERT_KEY", 3, 1, 9, "OBSERVED_AFTER_WRITE"),
            new SortFrame.SortedRegion(0, 3, "VIOLATED", "SCAN", 2, 9, 7),
            "BEFORE_LOCATION");
    var visualState =
        state(
            "sort-state",
            0,
            "insertion-sort",
            array("values", "values-id", "AVAILABLE", capturedValues, 6, true),
            pointers,
            sort);

    var result =
        mapper.map(
            List.of(observation(12, List.of(visualState), List.of())),
            List.of(hint("insertion-sort", "sort", Map.of("array", "values"))));
    var normalized = result.frames().get(0).states().get(0);

    assertThat(normalized.phase()).isEqualTo("INNER_COMPARE");
    assertThat(normalized.snapshotPhase()).isEqualTo("BEFORE_LOCATION");
    assertThat(normalized.sortedRegion()).isEqualTo(sort.sortedRegion());
    assertThat(normalized.operations())
        .extracting(NormalizedVisualizationResponse.Operation::type)
        .containsExactly("COMPARISON", "MUTATION", "MUTATION");
    assertThat(normalized.operations().get(0))
        .extracting(
            NormalizedVisualizationResponse.Operation::kind,
            NormalizedVisualizationResponse.Operation::firstIndex,
            NormalizedVisualizationResponse.Operation::secondIndex,
            NormalizedVisualizationResponse.Operation::firstValue,
            NormalizedVisualizationResponse.Operation::secondValue,
            NormalizedVisualizationResponse.Operation::swapRequired,
            NormalizedVisualizationResponse.Operation::action,
            NormalizedVisualizationResponse.Operation::actionRequired,
            NormalizedVisualizationResponse.Operation::lifecycle,
            NormalizedVisualizationResponse.Operation::sourceStatus,
            NormalizedVisualizationResponse.Operation::provenance)
        .containsExactly(
            "COMPARISON",
            0,
            4,
            9,
            null,
            true,
            "SWAP",
            true,
            "UNKNOWN",
            "AVAILABLE_FROM_SNAPSHOT",
            new NormalizedVisualizationResponse.Provenance(
                "SortFrame", "comparison", 12, "INNER_COMPARE", "BEFORE_LOCATION"));
    assertThat(normalized.operations().get(1))
        .extracting(
            NormalizedVisualizationResponse.Operation::kind,
            NormalizedVisualizationResponse.Operation::fromIndex,
            NormalizedVisualizationResponse.Operation::toIndex,
            NormalizedVisualizationResponse.Operation::value,
            NormalizedVisualizationResponse.Operation::lifecycle,
            NormalizedVisualizationResponse.Operation::sourceStatus,
            NormalizedVisualizationResponse.Operation::provenance)
        .containsExactly(
            "SHIFT_RIGHT",
            1,
            2,
            7,
            "PENDING",
            "PENDING_BEFORE_LOCATION",
            new NormalizedVisualizationResponse.Provenance(
                "SortFrame", "pendingMutation", 12, "INNER_COMPARE", "BEFORE_LOCATION"));
    assertThat(normalized.operations().get(2))
        .extracting(
            NormalizedVisualizationResponse.Operation::kind,
            NormalizedVisualizationResponse.Operation::fromIndex,
            NormalizedVisualizationResponse.Operation::toIndex,
            NormalizedVisualizationResponse.Operation::value,
            NormalizedVisualizationResponse.Operation::lifecycle,
            NormalizedVisualizationResponse.Operation::sourceStatus,
            NormalizedVisualizationResponse.Operation::provenance)
        .containsExactly(
            "INSERT_KEY",
            3,
            1,
            9,
            "OBSERVED",
            "OBSERVED_AFTER_WRITE",
            new NormalizedVisualizationResponse.Provenance(
                "SortFrame", "completedMutation", 12, "INNER_COMPARE", "BEFORE_LOCATION"));
    assertThat(normalized.highlights())
        .extracting(NormalizedVisualizationResponse.Highlight::index)
        .containsExactly(1L, 0L, 1L, 2L, 1L);
    assertThat(normalized.highlights())
        .allSatisfy(highlight -> assertThat(highlight.index()).isBetween(0L, 2L));
    assertThat(normalized.array().values()).containsExactly(9, null, 7);
  }

  @Test
  void preservesNonSortDataWithoutInventingOperationsAndFreezesOutput() throws Exception {
    // Break protected: an inferred operation from a VisualEvent falsely claims an atomic algorithm
    // action.
    var state =
        state(
            "search",
            0,
            "binary-search",
            array("values", "values-id", "UNAVAILABLE", List.of(), null, false),
            Map.of("mid", new VisualState.Pointer("mid", "mid-id", 1L, "ARRAY_UNAVAILABLE")),
            null);
    var result =
        mapper.map(
            List.of(observation(1, List.of(state), List.of())),
            List.of(hint("binary-search", "array-search", Map.of("array", "values"))));
    var normalized = result.frames().get(0).states().get(0);

    assertThat(normalized.operations()).isEmpty();
    assertThat(normalized.highlights()).isEmpty();
    assertThat(normalized.array()).isEqualTo(state.array());
    assertThat(normalized.pointers()).isEqualTo(state.pointers());
    assertThatThrownBy(() -> result.frames().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> normalized.pointers().put("right", null))
        .isInstanceOf(UnsupportedOperationException.class);
    var json = new ObjectMapper().writeValueAsString(mapper.map(List.of(), List.of()));
    assertThat(json).contains("\"schemaVersion\":\"1.0\"", "\"algorithms\":[]", "\"frames\":[]");
  }

  @Test
  void freezesNestedSnapshotValuesInsteadOfSharingMutableSourceData() {
    // Break protected: mutating a captured JSON-like value after mapping changes a published frame.
    var mutable = new LinkedHashMap<String, Object>();
    mutable.put("before", 1);
    var array = array("values", "values-id", "AVAILABLE", List.of(mutable), 1, false);
    var state =
        new VisualState(
            "state",
            0,
            "binary-search",
            "plan",
            "READY",
            array,
            Map.of(),
            Map.of("key", new VisualState.Scalar("key", "key-id", mutable, "AVAILABLE")),
            null,
            List.of(),
            null);
    var result =
        mapper.map(
            List.of(
                observation(
                    1,
                    List.of(state),
                    List.of(
                        new VisualEvent(
                            "state", "VALUE_CHANGED", "key", null, 1, array, mutable)))),
            List.of(hint("binary-search", "array-search", Map.of("array", "values"))));

    mutable.put("after", 2);

    var normalized = result.frames().get(0).states().get(0);
    assertThat(map(normalized.array().values().get(0)))
        .containsEntry("before", 1)
        .doesNotContainKey("after");
    assertThat(map(normalized.scalars().get("key").value()))
        .containsEntry("before", 1)
        .doesNotContainKey("after");
    var before = (VisualState.ArrayValue) result.frames().get(0).changes().get(0).before();
    assertThat(map(before.values().get(0))).containsEntry("before", 1).doesNotContainKey("after");
  }

  @Test
  void emitsDeterministicRoleMapAndHighlightOrderingRegardlessOfSourceMapInsertionOrder()
      throws Exception {
    // Break protected: source map insertion order changes the JSON playback contract.
    var firstPointers = new LinkedHashMap<String, VisualState.Pointer>();
    firstPointers.put("right", new VisualState.Pointer("right", "right-id", 1L, "VALID"));
    firstPointers.put("left", new VisualState.Pointer("left", "left-id", 0L, "VALID"));
    var secondPointers = new LinkedHashMap<String, VisualState.Pointer>();
    secondPointers.put("left", new VisualState.Pointer("left", "left-id", 0L, "VALID"));
    secondPointers.put("right", new VisualState.Pointer("right", "right-id", 1L, "VALID"));
    var first =
        state(
            "state",
            0,
            "two-pointers",
            array("values", "id", "AVAILABLE", List.of(1, 2), 2, false),
            firstPointers,
            null);
    var second =
        state(
            "state",
            0,
            "two-pointers",
            array("values", "id", "AVAILABLE", List.of(1, 2), 2, false),
            secondPointers,
            null);
    var hints =
        List.of(hint("two-pointers", "array-pointers", Map.of("right", "right", "left", "left")));

    var json = new ObjectMapper();
    var firstJson =
        json.writeValueAsString(
            mapper.map(List.of(observation(1, List.of(first), List.of())), hints));
    var secondJson =
        json.writeValueAsString(
            mapper.map(List.of(observation(1, List.of(second), List.of())), hints));

    assertThat(firstJson).isEqualTo(secondJson);
    assertThat(
            mapper
                .map(List.of(observation(1, List.of(first), List.of())), hints)
                .frames()
                .get(0)
                .states()
                .get(0)
                .highlights())
        .extracting(NormalizedVisualizationResponse.Highlight::role)
        .containsExactly("left", "right");
  }

  @Test
  void retainsUnknownMutationEvidenceWhenOtherSortSlotsAreMissing() {
    var sort =
        new SortFrame(
            "AFTER_COMPARE",
            null,
            new SortFrame.Mutation("UPDATE_SELECTED_INDEX", null, 2, null, null),
            null,
            null,
            null);
    var result =
        mapper.map(
            List.of(
                observation(
                    3,
                    List.of(
                        state(
                            "selection",
                            0,
                            "selection-sort",
                            array("values", "id", "AVAILABLE", List.of(1, 2, 3), 3, false),
                            Map.of(),
                            sort)),
                    List.of())),
            List.of(hint("selection-sort", "sort", Map.of("array", "values"))));

    var operation = result.frames().get(0).states().get(0).operations().get(0);
    assertThat(operation)
        .extracting(
            NormalizedVisualizationResponse.Operation::type,
            NormalizedVisualizationResponse.Operation::kind,
            NormalizedVisualizationResponse.Operation::fromIndex,
            NormalizedVisualizationResponse.Operation::toIndex,
            NormalizedVisualizationResponse.Operation::value,
            NormalizedVisualizationResponse.Operation::lifecycle,
            NormalizedVisualizationResponse.Operation::sourceStatus,
            NormalizedVisualizationResponse.Operation::provenance)
        .containsExactly(
            "MUTATION",
            "UPDATE_SELECTED_INDEX",
            null,
            2,
            null,
            "UNKNOWN",
            null,
            new NormalizedVisualizationResponse.Provenance(
                "SortFrame", "pendingMutation", 3, "AFTER_COMPARE", null));
  }

  @Test
  void preservesUnavailableArrayStatusesAndNeverCarriesStateOrEventsForward() {
    var statuses = List.of("MISSING", "NULL", "UNAVAILABLE", "UNSUPPORTED", "AVAILABLE");
    var states = new ArrayList<VisualState>();
    for (int index = 0; index < statuses.size(); index++) {
      states.add(
          state(
              "state-" + index,
              0,
              "binary-search",
              array("values", "id-" + index, statuses.get(index), List.of(), 0, false),
              Map.of("mid", new VisualState.Pointer("mid", "mid-id", null, statuses.get(index))),
              null));
    }
    states.add(state("state-without-array", 0, "binary-search", null, Map.of(), null));
    var first = observation(4, states, List.of());
    var second =
        observation(
            5,
            List.of(),
            List.of(new VisualEvent("state-0", "STATE_LEFT", "state", 4L, 5, null, null)));
    var result =
        mapper.map(
            List.of(first, second),
            List.of(hint("binary-search", "array-search", Map.of("array", "values"))));

    assertThat(result.frames().get(0).states())
        .extracting(state -> state.array() == null ? null : state.array().status())
        .containsExactly("MISSING", "NULL", "UNAVAILABLE", "UNSUPPORTED", "AVAILABLE", null);
    assertThat(result.frames().get(0).states())
        .allSatisfy(state -> assertThat(state.highlights()).isEmpty());
    assertThat(result.frames().get(1).states()).isEmpty();
    assertThat(result.frames().get(1).changes())
        .containsExactly(
            new NormalizedVisualizationResponse.Change(
                "state-0", "STATE_LEFT", "state", 4L, 5, null, null));
    assertThat(
            mapper.map(
                List.of(),
                List.of(hint("binary-search", "array-search", Map.of("array", "values")))))
        .extracting(
            NormalizedVisualizationResponse::schemaVersion,
            NormalizedVisualizationResponse::algorithms,
            NormalizedVisualizationResponse::frames)
        .containsExactly("1.0", List.of(result.algorithms().get(0)), List.of());
  }

  @Test
  void canonicalizesNestedJsonObjectKeysAcrossArrayScalarOperationAndChangePayloads()
      throws Exception {
    // Break protected: equivalent JSON objects with different source insertion orders serialize
    // differently.
    var firstPayload = nestedPayload("z", 9, "a", 1);
    var secondPayload = nestedPayload("a", 1, "z", 9);
    var first = payloadObservation(firstPayload);
    var second = payloadObservation(secondPayload);
    var hints = List.of(hint("bubble-sort", "sort", Map.of("array", "values")));

    var json = new ObjectMapper();

    assertThat(json.writeValueAsString(mapper.map(List.of(first), hints)))
        .isEqualTo(json.writeValueAsString(mapper.map(List.of(second), hints)));
  }

  @Test
  void serializesRequiredNonemptyContractPathsWithLiteralPayloadValues() throws Exception {
    var payload = nestedPayload("z", 9, "a", 1);
    var output =
        mapper.map(
            List.of(payloadObservation(payload)),
            List.of(hintWithSort("bubble-sort", "sort", Map.of("array", "values"))));

    var root = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(output));

    assertThat(root.at("/schemaVersion").asText()).isEqualTo("1.0");
    assertThat(root.at("/algorithms/0/id").asText()).isEqualTo("hint-0");
    assertThat(root.at("/algorithms/0/confidence").asDouble()).isEqualTo(0.8);
    assertThat(root.at("/algorithms/0/patternAstNodeId").asText()).isEqualTo("pattern-id");
    assertThat(root.at("/algorithms/0/methodAstNodeId").asText()).isEqualTo("method-id");
    assertThat(root.at("/algorithms/0/sourceRange/startLine").asInt()).isEqualTo(3);
    assertThat(root.at("/algorithms/0/sourceRange/endLine").asInt()).isEqualTo(9);
    assertThat(root.at("/algorithms/0/evidence/0").asText()).isEqualTo("evidence");
    assertThat(root.at("/algorithms/0/variables/array").asText()).isEqualTo("values");
    assertThat(root.at("/algorithms/0/variableDeclarationIds/array").asText())
        .isEqualTo("array-declaration");
    assertThat(root.at("/algorithms/0/sort/schemaVersion").asInt()).isEqualTo(1);
    assertThat(root.at("/frames/0/sequence").asLong()).isEqualTo(1L);
    assertThat(root.at("/frames/0/snapshotPhase").asText()).isEqualTo("BEFORE_LOCATION");
    assertThat(root.at("/frames/0/granularity").asText()).isEqualTo("LINE_BREAKPOINT");
    assertThat(root.at("/frames/0/source/astNodeId").asText()).isEqualTo("node-1");
    assertThat(root.at("/frames/0/source/lineNumber").asInt()).isEqualTo(1);
    assertThat(root.at("/frames/0/context/threadId").asLong()).isEqualTo(1L);
    assertThat(root.at("/frames/0/context/methodName").asText()).isEqualTo("main");
    assertThat(root.at("/frames/0/states/0/id").asText()).isEqualTo("payload");
    assertThat(root.at("/frames/0/states/0/array/values/0/nested/a").asInt()).isEqualTo(1);
    assertThat(root.at("/frames/0/states/0/scalars/key/value/nullable").isNull()).isTrue();
    assertThat(root.at("/frames/0/states/0/operations/0/type").asText()).isEqualTo("COMPARISON");
    assertThat(root.at("/frames/0/states/0/operations/0/firstIndex").asInt()).isZero();
    assertThat(root.at("/frames/0/states/0/operations/0/secondIndex").asInt()).isEqualTo(1);
    assertThat(root.at("/frames/0/states/0/operations/0/firstValue/nested/z").asInt()).isEqualTo(9);
    assertThat(root.at("/frames/0/states/0/operations/0/value").isNull()).isTrue();
    assertThat(root.at("/frames/0/changes/0/stateId").asText()).isEqualTo("payload");
    assertThat(root.at("/frames/0/changes/0/fromSequence").isNull()).isTrue();
    assertThat(root.at("/frames/0/changes/0/before/nullable").isNull()).isTrue();
  }

  private AlgorithmHint hint(String type, String plan, Map<String, String> variables) {
    return new AlgorithmHint(
        type,
        0.8,
        plan,
        List.of("evidence"),
        variables,
        3,
        9,
        "pattern-id",
        "method-id",
        Map.of("array", "array-declaration"));
  }

  private AlgorithmHint hintWithSort(String type, String plan, Map<String, String> variables) {
    return new AlgorithmHint(
        type,
        0.8,
        plan,
        List.of("evidence"),
        variables,
        3,
        9,
        "pattern-id",
        "method-id",
        Map.of("array", "array-declaration"),
        new SortPattern(
            1,
            SortPattern.Direction.ASCENDING,
            "inner-loop",
            "comparison",
            "condition",
            List.of(),
            new SortPattern.Operand("first", 0),
            new SortPattern.Operand("second", 1)));
  }

  private ExecutionObservation payloadObservation(Map<String, Object> payload) {
    var sort =
        new SortFrame(
            "COMPARE",
            new SortFrame.Comparison(0, 1, payload, payload, true, "AVAILABLE_FROM_SNAPSHOT"),
            null,
            null,
            null,
            "BEFORE_LOCATION");
    var state =
        new VisualState(
            "payload",
            0,
            "bubble-sort",
            "sort",
            "READY",
            array("values", "values-id", "AVAILABLE", List.of(payload), 1, false),
            Map.of(),
            Map.of("key", new VisualState.Scalar("key", "key-id", payload, "AVAILABLE")),
            null,
            List.of(),
            sort);
    return observation(
        1,
        List.of(state),
        List.of(new VisualEvent("payload", "VALUE_CHANGED", "key", null, 1, payload, payload)));
  }

  private Map<String, Object> nestedPayload(
      String firstKey, int firstValue, String secondKey, int secondValue) {
    var nested = new LinkedHashMap<String, Object>();
    nested.put(firstKey, firstValue);
    nested.put(secondKey, secondValue);
    var payload = new LinkedHashMap<String, Object>();
    payload.put("nested", nested);
    payload.put("nullable", null);
    return payload;
  }

  private ExecutionObservation observation(
      long sequence, List<VisualState> states, List<VisualEvent> events) {
    return new ExecutionObservation(
        sequence,
        "node-" + sequence,
        (int) sequence,
        (int) sequence,
        (int) sequence,
        2,
        15,
        "ExpressionStmt",
        "value++;",
        List.of(),
        new ExecutionContext(1, "main", "Sample", "main", "()V", 1, sequence),
        states,
        events);
  }

  private VisualState state(
      String id,
      int hintIndex,
      String algorithm,
      VisualState.ArrayValue array,
      Map<String, VisualState.Pointer> pointers,
      SortFrame sortFrame) {
    return new VisualState(
        id,
        hintIndex,
        algorithm,
        "plan",
        "READY",
        array,
        pointers,
        Map.of("key", new VisualState.Scalar("key", "key-id", null, "MISSING")),
        null,
        List.of("note"),
        sortFrame);
  }

  private VisualState.ArrayValue array(
      String name,
      String id,
      String status,
      List<Object> values,
      Integer length,
      boolean truncated) {
    return new VisualState.ArrayValue(name, id, status, values, length, truncated);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
