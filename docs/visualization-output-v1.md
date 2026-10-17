# Unified algorithm visualization output v1.0

## Endpoint and compatibility

`POST /api/debug/steps` accepts `multipart/form-data` with one `file` containing
a `.java` source file. The existing compile, JDI execution and trace generation
run once per request. Normalization reads the enriched execution observations.

The response retains `steps`, `warnings`, and `algorithmHints` and adds
`visualization`. Existing `steps[].visualStates`, `visualEvents`, and `sortFrame`
remain unchanged. `/api/debug` continues to return the legacy observation array.
Existing two- and three-argument `DebugStepsResponse` constructors remain usable.
The current standalone UI can continue using the legacy fields.

The new frontend should consume `visualization` after checking
`visualization.schemaVersion === "1.0"`. Unsupported schema versions should
fall back to a supported renderer or show a clear unsupported-version message.

## Timeline and algorithm identities

`visualization.algorithms` contains every detected hint, including hints whose
code never executed. Each entry has `id`, `hintIndex`, `type`, `visualPlan`,
`confidence`, `patternAstNodeId`, `methodAstNodeId`, and `sourceRange` with
`startLine` and `endLine`. It also retains `evidence`, `variables`,
`variableDeclarationIds`, and optional `sort` AST metadata. Confidence is a
heuristic score, not a probability or proof of correctness.

IDs such as `hint-0` are local to this response. Reset the algorithm registry
when loading a different debug run. Source-range-based AST IDs are preserved;
variable names alone are not suitable for joins across methods or scopes.

`visualization.frames` has exactly one entry per execution observation, in the
same order as `steps`. Frames without visual states remain in the timeline.
Each frame contains `sequence`, `snapshotPhase`, `granularity`, `source`,
`context`, `states`, and `changes`. Preserve the frame array order for playback;
`sequence` identifies a snapshot, rather than a duration, source line order or
iteration number. Repeated source lines and repeated method calls remain
distinct snapshots. `context` preserves thread, method signature, stack depth
and bytecode position; it is not a globally unique invocation identifier.

`source` contains `astNodeId` (the statement ID), `lineNumber`, `startLine`,
`endLine`, `startColumn`, and `endColumn`, copied from that observation. Lines
and columns are one-based when available. Use this source for highlighting
the active location rather than reconstructing it from an algorithm's range.

## States and rendering data

A frame may contain multiple states. Render each selected state independently;
do not assume `states[0]` represents the algorithm selected by the user.
If a source state has an invalid hint index, its data is retained with a null
`algorithmId`; it is not joined to an arbitrary hint by variable name.

| Field | Meaning |
| --- | --- |
| `id` | Original visual state identity used by change events. |
| `algorithmId` | Link to the response-local algorithm metadata. |
| `status` | Original rendering availability: `READY`, `PARTIAL`, or `UNAVAILABLE`. |
| `array` | Name, declaration AST ID, status, captured values, actual length, truncation. |
| `pointers` | Role-keyed variable name, declaration ID, index and original status. |
| `scalars` | Role-keyed variable name, declaration ID, value and original status. |
| `range` | Existing half-open span `[start, endExclusive)` with kind/status. |
| `operations` | Independent comparison and mutation evidence from the sort strategy. |
| `highlights` | Drawable current captured cells; never substitutes for statuses. |
| `sortedRegion` | Optional original runtime order-check result, including violations. |
| `notes` | Original semantic caveats and partial-data notes. |
| `phase`, `snapshotPhase` | Optional original sorting-frame phase and snapshot phase. |

`READY` means data can be rendered; it does not mean the algorithm finished or
is correct. A terminal frame is not automatically a successful result.

Array statuses are `AVAILABLE`, `MISSING`, `NULL`, `UNAVAILABLE`, or
`UNSUPPORTED`. `values` contains only the captured prefix; `length` is the
actual runtime array length when known. For example, length 120 with 100 values
and `truncated: true` does not provide values at indices 100 through 119.
Legitimate null elements stay null, empty arrays stay empty, and absent values
are never replaced with zero. Missing data is not carried from earlier frames.

Pointer statuses preserve these distinctions:

| Status | Rendering implication |
| --- | --- |
| `VALID` | Current index addresses a captured cell. |
| `NOT_CAPTURED` | Index is inside actual length but beyond the captured prefix. |
| `OUT_OF_BOUNDS` | Index is outside the actual array. |
| `MISSING`, `NULL`, `UNAVAILABLE` | Current variable value is unavailable or null. |
| `INVALID` | Variable cannot be used as an integer index. |
| `ARRAY_UNAVAILABLE` | Array length is unavailable, so bounds cannot be determined. |

Keep invalid and uncaptured pointer information visible in labels, without
drawing a marker on a fabricated array cell. Range statuses are `VALID`,
`EMPTY`, `UNKNOWN`, and `INVALID`. A sliding-window range is a candidate span;
it does not prove that the accumulator currently contains exactly those cells.
Binary-search pointer spans do not establish closed versus half-open bounds.
Scalar records retain `variableName`, `astNodeId`, `value`, and `status`.
Consult the status even when `value` is null; a missing variable is not zero.

## Operations and snapshot semantics

Every execution frame has `snapshotPhase: "BEFORE_LOCATION"` and
`granularity: "LINE_BREAKPOINT"`. A snapshot precedes the suspended bytecode
location; it does not imply the whole displayed source statement has run.

Each available SortFrame slot produces a separate operation, ordered as
comparison, pending mutation, completed mutation. One state may contain all
three simultaneously. Operations preserve original indices, values, comparison
booleans, action and status. Missing operands stay unknown; insertion's key
operand may legitimately have no array index.

| Lifecycle | Evidence |
| --- | --- |
| `PENDING` | A pending mutation has source status `PENDING_BEFORE_LOCATION`. |
| `OBSERVED` | A completed mutation has source status `OBSERVED_AFTER_WRITE`, reported by the runtime strategy. |
| `UNKNOWN` | Comparison data, unavailable operands or an unrecognized status; no completion inference. |

`AVAILABLE_FROM_SNAPSHOT` makes comparison operands available; its normalized
comparison lifecycle is `UNKNOWN`. Original mutation kinds, such as
`SAVE_LEFT_VALUE`, `WRITE_RIGHT_VALUE_TO_LEFT`, `WRITE_SAVED_VALUE_TO_RIGHT`,
`SWAP_COMPLETED`, `SELF_SWAP_COMPLETED`, `UPDATE_SELECTED_INDEX`, `SHIFT_RIGHT`,
`SHIFT_RIGHT_COMPLETED`, `INSERT_KEY`, and `INSERT_KEY_COMPLETED`, remain intact.
The mapper does not claim an observed selected-index update if the strategy
only provides a pending update.

Provenance identifies the reporting `SortFrame` slot and snapshot. For a
completed write it points to where completion was observed, not necessarily
to the AST node that performed the write. No extra JDI execution is performed.

| Operation field | Meaning |
| --- | --- |
| `type` | `COMPARISON` or `MUTATION`. |
| `kind` | `COMPARISON` or the original mutation kind. |
| `firstIndex`, `secondIndex` | Comparison array indices when available. |
| `firstValue`, `secondValue` | Comparison operands, including insertion key. |
| `fromIndex`, `toIndex`, `value` | Original mutation indices and supplied value. |
| `swapRequired` | Original nullable comparison swap decision. |
| `action`, `actionRequired` | Original nullable comparison action and decision. |
| `lifecycle`, `sourceStatus` | Normalized evidence category and unchanged original status. |
| `provenance` | `source`, `field`, `sequence`, `phase`, `snapshotPhase`. |

Irrelevant operation fields can be null. Preserve the distinction between a
null value, an unavailable operand, and an operand with no array index by
consulting `sourceStatus`, the operation type, and current variable statuses.

`sortedRegion` preserves bounds, status, basis, and optional `violationIndex`,
`previousValue`, `currentValue`. `VERIFIED_SORTED` means the strategy checked
the captured runtime values in the detected direction. It may refer to a
prefix/suffix, not the entire array. `NOT_SORTED` records an observed inversion;
`UNKNOWN` and `TRUNCATED` are not verification successes.

Binary search, two pointers and sliding window expose their existing
arrays/pointers/scalars/ranges and return empty operations lists. The mapper
does not invent search outcomes, targets, comparison results, swaps, window
membership or algorithm completion.

## Snapshot changes

`changes` preserves every VisualEvent field: `stateId`, `type`, `target`,
`fromSequence`, `toSequence`, `before`, `after`. These are snapshot differences,
not atomic operation proofs. Event types include `STATE_ENTERED`, `STATE_RESET`,
`STATE_LEFT`, `ARRAY_CHANGED`, `POINTER_CHANGED`, `VALUE_CHANGED`, and
`RANGE_CHANGED`. Entry/reset events can have null `fromSequence`, `before` and
`after`. `STATE_LEFT` can occur in a frame whose `states` is empty.

An `ARRAY_CHANGED` event is never converted into a swap. On entry/reset/leave,
the frontend should reset its transient animation state; it must not carry
missing values into the new snapshot.

## Limits

All six supported algorithm types share the output contract; fidelity depends
on the evidence supplied by the existing strategy. Missing SortFrame data
results in empty operations; unsupported values retain their source status.
Truncated captures, missing variables and out-of-bounds pointers are input
limitations, not automatic normalization failures. Normalization does not
expand detector coverage or independently verify algorithm correctness.

Repeated invocations, loops and threads use existing observation context and
state events. The contract does not add a new invocation ID or infer causality
across gaps. Unknown future source statuses remain preserved and yield unknown
operation lifecycle rather than being treated as completed.

## Illustrative JSON excerpts

The following are illustrative excerpts of `visualization`. They omit unrelated
metadata, other frames, context and some state fields for readability. AST IDs
and sequences are illustrative; no Java execution is claimed by these examples.
Actual responses include the complete record fields described above. Optional
data can be null; consumers should tolerate both null and absent optional fields.

### Bubble Sort: final swap write is still pending

At this snapshot, the left write has made the captured array `[1, 1]`. The value
2 is still saved in the temporary variable; it is not yet at index 1. A later
strategy-confirmed snapshot may report `SWAP_COMPLETED` with lifecycle `OBSERVED`.

```json
{
  "schemaVersion": "1.0",
  "algorithms": [{
    "id": "hint-0", "hintIndex": 0, "type": "bubble-sort",
    "visualPlan": "array-sort", "confidence": 0.95,
    "patternAstNodeId": "ast-ForStmt-4-5-12-5",
    "methodAstNodeId": "ast-MethodDeclaration-2-3-14-3",
    "sourceRange": {"startLine": 4, "endLine": 12}
  }],
  "frames": [{
    "sequence": 8, "snapshotPhase": "BEFORE_LOCATION",
    "granularity": "LINE_BREAKPOINT",
    "source": {
      "astNodeId": "ast-ExpressionStmt-9-11-9-32",
      "lineNumber": 9, "startLine": 9, "endLine": 9,
      "startColumn": 11, "endColumn": 32
    },
    "states": [{
      "id": "hint-0", "algorithmId": "hint-0", "status": "PARTIAL",
      "array": {
        "variableName": "nums", "astNodeId": "ast-VariableDeclarator-3-11-3-23",
        "status": "AVAILABLE", "values": [1, 1], "length": 2, "truncated": false
      },
      "operations": [{
        "type": "MUTATION", "kind": "WRITE_SAVED_VALUE_TO_RIGHT",
        "firstIndex": null, "secondIndex": null,
        "fromIndex": 0, "toIndex": 1,
        "firstValue": null, "secondValue": null, "value": 2,
        "swapRequired": null, "action": null, "actionRequired": null,
        "lifecycle": "PENDING", "sourceStatus": "PENDING_BEFORE_LOCATION",
        "provenance": {
          "source": "SortFrame", "field": "pendingMutation", "sequence": 8,
          "phase": "SWAP_RIGHT_WRITE_PENDING", "snapshotPhase": "BEFORE_LOCATION"
        }
      }],
      "highlights": [
        {"kind": "MUTATION", "index": 0, "role": "from", "source": "pendingMutation"},
        {"kind": "MUTATION", "index": 1, "role": "to", "source": "pendingMutation"}
      ],
      "sortedRegion": {
        "start": 2, "endExclusive": 2, "status": "EMPTY",
        "basis": "BUBBLE_SUFFIX_RUNTIME_CHECK"
      },
      "phase": "SWAP_RIGHT_WRITE_PENDING", "snapshotPhase": "BEFORE_LOCATION"
    }],
    "changes": []
  }]
}
```

Highlight excerpts above omit the pass/scan pointer markers. Comparisons, when
present, use `type` and `kind` equal to `COMPARISON`, retain `firstIndex`,
`secondIndex`, `firstValue`, `secondValue`, `swapRequired`, `action` and
`actionRequired`, and carry lifecycle `UNKNOWN` even for available operands.

### Binary Search: observed pointers without an inferred result

```json
{
  "schemaVersion": "1.0",
  "algorithms": [{
    "id": "hint-0", "hintIndex": 0, "type": "binary-search",
    "visualPlan": "array-pointers", "confidence": 0.9,
    "patternAstNodeId": "ast-WhileStmt-5-5-10-5",
    "methodAstNodeId": "ast-MethodDeclaration-2-3-11-3",
    "sourceRange": {"startLine": 5, "endLine": 10}
  }],
  "frames": [{
    "sequence": 5, "snapshotPhase": "BEFORE_LOCATION",
    "granularity": "LINE_BREAKPOINT",
    "source": {
      "astNodeId": "ast-IfStmt-7-7-7-35",
      "lineNumber": 7, "startLine": 7, "endLine": 7,
      "startColumn": 7, "endColumn": 35
    },
    "states": [{
      "id": "hint-0", "algorithmId": "hint-0", "status": "READY",
      "array": {
        "variableName": "nums", "astNodeId": "ast-VariableDeclarator-3-11-3-29",
        "status": "AVAILABLE", "values": [1, 3, 5, 7], "length": 4, "truncated": false
      },
      "pointers": {
        "left": {"variableName": "lo", "astNodeId": "ast-VariableDeclarator-4-9-4-14", "index": 0, "status": "VALID"},
        "mid": {"variableName": "mid", "astNodeId": "ast-VariableDeclarator-6-11-6-37", "index": 1, "status": "VALID"},
        "right": {"variableName": "hi", "astNodeId": "ast-VariableDeclarator-4-17-4-36", "index": 3, "status": "VALID"}
      },
      "scalars": {},
      "range": {"kind": "POINTER_SPAN", "start": 0, "endExclusive": 4, "status": "VALID"},
      "operations": [],
      "highlights": [{"kind": "POINTER", "index": 1, "role": "mid", "source": "pointer"}],
      "notes": ["POINTER_SPAN_DOES_NOT_DECLARE_SEARCH_BOUND_CONVENTION"]
    }],
    "changes": []
  }]
}
```

The highlight excerpt omits left/right markers. No target, found/not-found
outcome or comparison execution is implied by this payload.

### Fixed Sliding Window: candidate range and pre-addition accumulator

At `right == 2`, the displayed accumulator can still be 3 before adding
`nums[2]`. The candidate range `[1, 3)` is rendering context, not proof that
the accumulator already equals the sum of those two elements.

```json
{
  "schemaVersion": "1.0",
  "algorithms": [{
    "id": "hint-0", "hintIndex": 0, "type": "sliding-window",
    "visualPlan": "array-window", "confidence": 0.8,
    "patternAstNodeId": "ast-ForStmt-5-5-8-5",
    "methodAstNodeId": "ast-MethodDeclaration-2-3-9-3",
    "sourceRange": {"startLine": 5, "endLine": 8}
  }],
  "frames": [{
    "sequence": 9, "snapshotPhase": "BEFORE_LOCATION",
    "granularity": "LINE_BREAKPOINT",
    "source": {
      "astNodeId": "ast-ExpressionStmt-6-7-6-25",
      "lineNumber": 6, "startLine": 6, "endLine": 6,
      "startColumn": 7, "endColumn": 25
    },
    "states": [{
      "id": "hint-0", "algorithmId": "hint-0", "status": "READY",
      "array": {
        "variableName": "nums", "astNodeId": "ast-VariableDeclarator-3-11-3-29",
        "status": "AVAILABLE", "values": [1, 2, 3, 4], "length": 4, "truncated": false
      },
      "pointers": {
        "right": {"variableName": "end", "astNodeId": "ast-VariableDeclarator-5-14-5-20", "index": 2, "status": "VALID"}
      },
      "scalars": {
        "accumulator": {"variableName": "total", "astNodeId": "ast-VariableDeclarator-4-9-4-17", "value": 3, "status": "AVAILABLE"},
        "windowSize": {"variableName": "k", "astNodeId": "ast-VariableDeclarator-4-20-4-24", "value": 2, "status": "AVAILABLE"}
      },
      "range": {"kind": "FIXED_SIZE_CANDIDATE", "start": 1, "endExclusive": 3, "status": "VALID"},
      "operations": [],
      "highlights": [{"kind": "POINTER", "index": 2, "role": "right", "source": "pointer"}],
      "notes": ["RANGE_DOES_NOT_PROVE_ACCUMULATOR_MEMBERSHIP"]
    }],
    "changes": []
  }]
}
```

## Frontend playback example

These functions are renderer hooks, not changes to the existing frontend:

```javascript
function createPlayback(response, selectedAlgorithmId) {
  const output = response.visualization;
  if (output?.schemaVersion !== '1.0') throw new Error('Unsupported visualization version');
  const algorithms = new Map(output.algorithms.map(a => [a.id, a]));
  const algorithm = algorithms.get(selectedAlgorithmId);
  if (!algorithm) throw new Error('Unknown algorithm in this debug run');

  return function showFrame(frameIndex) {
    const frame = output.frames[frameIndex];
    if (!frame) return;
    highlightSource(frame.source, frame.sequence);
    // The sequence need not equal frameIndex + 1; keep the returned frame order.
    const states = frame.states.filter(s => s.algorithmId === algorithm.id);
    clearTransientAnimation();
    if (!states.length) return renderInactiveAlgorithm(algorithm);
    for (const state of states) {
      renderCapturedArray(state.array?.values ?? [], {
        status: state.array?.status,
        actualLength: state.array?.length,
        truncated: state.array?.truncated
      });
      renderPointerLabels(state.pointers); // Display missing/out-of-bounds labels too.
      renderHighlights(state.highlights); // Already limited to captured cells.
      renderScalarsAndRange(state.scalars, state.range, state.notes);
      renderSortedRegion(state.sortedRegion); // Check status and basis, not only bounds.
      for (const operation of state.operations) {
        if (operation.lifecycle === 'PENDING') renderPendingOperation(operation);
        else if (operation.lifecycle === 'OBSERVED') renderObservedOperation(operation);
        else renderOperationEvidence(operation); // E.g. available comparison operands.
      }
      renderAvailability(state.status);
    }
    renderSnapshotChanges(frame.changes); // Never infer swaps from ARRAY_CHANGED.
  };
}
```

Provide an algorithm selector from `algorithms`, and next/previous or scrubber
controls over `frames`. An empty trace needs an explicit no-observations view.
Frames can be scrubbed directly because each state contains its own snapshot;
changes are optional explanatory data, not required for reconstructing values.
