# Unified visualization output v1.0 design

## Intent and scope

Implement the supplied Unified Algorithm Visualization Output task in the current
workspace on `feature/unified-visualization-output`, starting from `9621bb1`.
The supplied task is the binding spec; this document freezes its concrete Java
interfaces. Continuous execution is explicitly authorized. No commit or push.

Existing `VisualTraceBuilder` already produces rendering data for six patterns.
No normalization layer exists. `UI/dashboardPage.html` consumes `/api/debug/steps`
and reads legacy `steps[].visualStates[].sortFrame`; it must continue to work.
The minimal change is two normalization production files, one response record
change, an explicit controller integration if useful, tests and documentation.
Nested records avoid six algorithm-specific adapters or a large DTO hierarchy.
No detector, JDI, strategy, frontend or dependency changes are needed.

## Output and mapping

Package: `com.example.astchunker.visualization.output`.

`VisualizationOutputMapper.map(List<ExecutionObservation> steps,
List<AlgorithmHint> hints)` returns `NormalizedVisualizationResponse`.
This stateless mapper reads already-enriched observations; it never runs JDI or
the compiler, mutates input, carries values forward or infers operations from
snapshot differences.

- Response: `schemaVersion: "1.0"`, `algorithms`, `frames`.
- Algorithm: `id: "hint-<index>"`, `hintIndex`, `type`, `visualPlan`, `confidence`,
  `patternAstNodeId`, `methodAstNodeId`, `sourceRange` (start/end lines), plus
  evidence, variable roles, declaration IDs and optional original `sort` metadata.
- Frame: `sequence`, `snapshotPhase`, `granularity`, `source`, `context`, `states`,
  `changes`. Exactly one frame per observation, in input execution order.
- Source: `astNodeId` from `statementAstNodeId`, `lineNumber`, `startLine`,
  `endLine`, `startColumn`, `endColumn` from that observation.
- State: original `id`, `algorithmId` joined by hint index (never variable name),
  original `status`, `array`, `pointers`, `scalars`, `range`, `operations`,
  `highlights`, optional `sortedRegion`, `notes`, optional sort `phase` and
  `snapshotPhase`. Preserve exact variable IDs, data statuses and nulls.
- Array retains `values`, actual `length`, `truncated`, name, ID and status.
  Reuse existing immutable value records wherever this preserves the contract.
- Operations: separate entries for comparison, pending mutation and completed
  mutation in that order. Preserve all original comparison and mutation fields;
  distinguish broad `type` (`COMPARISON`/`MUTATION`) from original mutation `kind`.
  Provenance names the exact SortFrame slot and the reporting snapshot sequence
  and phase; it does not claim which statement caused an observed mutation.
- Lifecycle: a pending mutation with `PENDING_BEFORE_LOCATION` is `PENDING`;
  a completed mutation with `OBSERVED_AFTER_WRITE` is `OBSERVED`; all other
  evidence is `UNKNOWN`, including snapshot-derived comparisons. Retain
  `sourceStatus`. Do not rename a completed kind into a pending kind.
- Highlights are optional rendering aids derived only from current valid,
  captured indices; no highlight for negative, uncaptured or unavailable values.
  Operations retain unavailable indices even when no highlight can be drawn.
- Changes copy all seven VisualEvent fields without causal reclassification.
- Preserve stable ordering; freeze DTO collections, including nested JSON values
  where applicable, without using null-rejecting copies for legitimate values.

## Integration and compatibility

Add `visualization` to `DebugStepsResponse`. Retain two- and three-argument
constructors; derive visualization from their steps/hints. The controller uses
the existing compiled target and enriched execution list exactly once. Existing
fields, constructors of the controller, `/api/debug` and runtime statuses remain
unchanged. The normalized output does not duplicate unrelated raw locals.

## Verification

Test-first unit fixtures cover metadata, ordering, source, multiple states,
operations, original statuses/provenance, events, all array/pointer edge cases,
unknown/missing data, immutability, and JSON. Integration executes actual Java
targets through JDI for all six patterns, ascending/descending sorts, both
window types, repeated invocations and multiple hints. API serialization must
contain both legacy and normalized structures with unchanged legacy semantics.
Run the full Maven test command, clean package, targeted Spotless apply/check,
and independent final review. Document exact command results and baseline
failures separately.
