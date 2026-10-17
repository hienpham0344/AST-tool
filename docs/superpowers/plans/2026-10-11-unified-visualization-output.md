# Unified Visualization Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an additive, deterministic `visualization` v1.0 response to `POST /api/debug/steps` for all six supported algorithms, without changing existing AST, JDI, or sort-trace semantics.

**Architecture:** Keep the existing execution pipeline intact and normalize only its enriched observations: `AstAnalyzer` identifies source nodes; `BreakpointMapper` turns them into target locations; `JdiSession` captures each hit; `VariableMapper` maps frame locals into `ExecutionObservation`; and `VisualTraceBuilder` appends independent `VisualState`/`VisualEvent` values. A new stateless `VisualizationOutputMapper` consumes that already-enriched list and the same `AlgorithmHint` list once, yielding a nested-record `NormalizedVisualizationResponse` that is added to `DebugStepsResponse`.

**Tech Stack:** Java 17 records, Spring Boot/Jackson, JavaParser, JDI, Maven, JUnit 5, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-11-unified-visualization-output-design.md` (frozen design; binding request: `C:\Users\HIEN\.codex\attachments\d579d84a-a433-4c2e-a02c-d87b7c9500a9\Pasted text.txt`)

## Global Constraints

- Remain in `backend/`; do not modify `algorithm/sort/` or `visualization/sort/`, add detectors, alter JDI launch/attach behavior, add dependencies, or touch the UI.
- `/api/debug` remains unchanged. `/api/debug/steps` retains `steps`, `warnings`, and `algorithmHints` exactly, and adds only `visualization`.
- The mapper must never mutate observations or carry a state/value forward. It emits one frame per input observation in input order, even with no states.
- Reuse immutable `VisualState.ArrayValue`, `Pointer`, `Scalar`, `IndexRange`, and `SortFrame.SortedRegion` values rather than copying or reinterpreting them. Freeze new collections in insertion order without null-rejecting copies: use unmodifiable `ArrayList`/`LinkedHashMap` snapshots where a legitimate nested JSON value can be null.
- `hint-<index>` is response-local. Preserve each source state's `id`, and set `algorithmId` from its `hintIndex` as `hint-<index>`.
- A comparison with `AVAILABLE_FROM_SNAPSHOT` describes available operands, not execution proof; it has lifecycle `UNKNOWN`. Map only `PENDING_BEFORE_LOCATION` to `PENDING` and `OBSERVED_AFTER_WRITE` to `OBSERVED`; every other status maps to `UNKNOWN`.
- A `VisualEvent` remains a snapshot difference: preserve its fields as a change and never turn `ARRAY_CHANGED` into a swap or another atomic operation. An invalid state hint index must not silently join a different algorithm.
- The repository-wide Spotless check currently reports 26 pre-existing violations. Format/check only the exact Task 1/2 Java paths with `-DspotlessFiles`; disclose the global baseline rather than applying unrelated formatting.
- Do not commit, push, merge, or modify files outside a task's ownership list.

## Confirmed Existing Boundaries

`DebugController` constructs the current grouped response at [DebugController.java](C:/Users/HIEN/Documents/2.Project/Test/AST-tool/backend/src/main/java/com/example/astchunker/controller/DebugController.java:58), after `VisualTraceBuilder.build(...)` at [DebugController.java](C:/Users/HIEN/Documents/2.Project/Test/AST-tool/backend/src/main/java/com/example/astchunker/controller/DebugController.java:66). `ExecutionObservation` already owns source location, variables, context, states/events, `snapshotPhase`, and `granularity` at [ExecutionObservation.java](C:/Users/HIEN/Documents/2.Project/Test/AST-tool/backend/src/main/java/com/example/astchunker/model/ExecutionObservation.java:7). The existing response has only three components and a compatible two-argument constructor at [DebugStepsResponse.java](C:/Users/HIEN/Documents/2.Project/Test/AST-tool/backend/src/main/java/com/example/astchunker/dto/DebugStepsResponse.java:8). `SortFrame` already distinguishes comparison, pending mutation, completed mutation, region, and snapshot phase at [SortFrame.java](C:/Users/HIEN/Documents/2.Project/Test/AST-tool/backend/src/main/java/com/example/astchunker/model/SortFrame.java:4).

## Contract to Implement

Create `com.example.astchunker.visualization.output.NormalizedVisualizationResponse` as one focused outer record with nested records, rather than a hierarchy of DTO files:

- `NormalizedVisualizationResponse(String schemaVersion, List<Algorithm> algorithms, List<Frame> frames)`, with schema version exactly `"1.0"`.
- `Algorithm(String id, int hintIndex, String type, String visualPlan, double confidence, String patternAstNodeId, String methodAstNodeId, SourceRange sourceRange, List<String> evidence, Map<String,String> variables, Map<String,String> variableDeclarationIds, SortPattern sort)`. `SourceRange` is `(int startLine, int endLine)` and `sort` is the source hint's optional sort metadata.
- `Frame(long sequence, String snapshotPhase, String granularity, Source source, ExecutionContext context, List<State> states, List<Change> changes)`, where `Source` is `(String astNodeId, int lineNumber, int startLine, int endLine, int startColumn, int endColumn)` and `Change` has exactly the seven `VisualEvent` fields (`stateId`, `type`, `target`, `fromSequence`, `toSequence`, `before`, `after`).
- `State(String id, String algorithmId, String status, ArrayValue array, Map<String,Pointer> pointers, Map<String,Scalar> scalars, IndexRange range, List<Operation> operations, List<Highlight> highlights, SortedRegion sortedRegion, List<String> notes, String phase, String snapshotPhase)`. `Highlight` is the compact `(String kind, long index, String role)` record; it never replaces or conceals the original pointer/status map.
- `Operation(String type, String kind, Integer firstIndex, Integer secondIndex, Integer fromIndex, Integer toIndex, Object firstValue, Object secondValue, Object value, Boolean swapRequired, String action, Boolean actionRequired, String lifecycle, String sourceStatus, Provenance provenance)`, where `Provenance` is `(String source, String field, long sequence, String phase, String snapshotPhase)`. Populate operations in fixed slot order: comparison, pending mutation, completed mutation. `type` is `COMPARISON` or `MUTATION`; comparison `kind` is exactly `COMPARISON`; mutation `kind` preserves the original mutation kind; provenance `source` is `SortFrame` and `field` is `comparison`, `pendingMutation`, or `completedMutation`.

For non-sort states, emit the available state data and an empty operations list. For sort states, preserve `SortFrame.phase` and `SortFrame.snapshotPhase` on the normalized state and preserve the immutable `SortedRegion` object unchanged. An operation preserves each explicit source field, including null where the source provides it; never manufacture a missing zero, null element replacement, target, completion, or highlight. `Highlight` is the compact `(String kind, long index, String role)` record: derive it only for current `VALID` pointers and comparison/mutation indices where the array is `AVAILABLE`, the index is non-null, non-negative, less than both actual `array.length()` and captured `array.values().size()`. Keep unavailable operation indices in their operation even when no highlight is drawable.

## Data Flow and Dependency Graph

```text
source upload
  -> AstAnalyzer (analysis + node IDs)
  -> TargetCompiler -> BreakpointMapper (eligible source locations)
  -> JdiSession (per-thread suspended frame) -> VariableMapper (scoped locals)
  -> List<ExecutionObservation> raw snapshots
  -> VisualTraceBuilder (VisualState, SortFrame, VisualEvent; no carry-forward)
  -> new VisualizationOutputMapper().map(enrichedSteps, hints)
  -> NormalizedVisualizationResponse
  -> DebugStepsResponse (legacy fields + visualization) -> Jackson JSON
```

```text
Task 1: normalized DTO + mapper + unit tests
   |
   v
Task 2: response/controller integration + JVM/HTTP contract tests + documentation
   |
   v
Task 3: full verification, Spotless check, diff review
```

No phase shares a writable file with another phase. Task 2 depends on Task 1's public records and `new VisualizationOutputMapper().map(...)`; Task 3 changes no files.

## Test Matrix

| Area | Unit tests | Real-JVM/integration tests |
| --- | --- | --- |
| Normalized DTO/mapper | Metadata/source/frame order, multiple states, immutable collections, events, null/missing/unsupported values, empty output, truncated arrays, invalid/uncaptured pointers, lifecycle mapping | N/A |
| Sorting normalization | Comparison, pending, completed, sorted region, phase/provenance/highlights from synthetic `SortFrame` | Bubble, selection, and insertion in ascending and descending cases |
| Non-sort normalization | Empty operations and preservation of pointers/range/scalars/events | Binary search, two pointers, fixed sliding window, variable sliding window |
| REST/backward compatibility | Record constructor fallback and Jackson serialization | MockMvc `/api/debug/steps`: legacy fields unchanged plus v1.0 visualization; `/api/debug` unchanged |

## Risks and Rollback

| Phase | Risk (likelihood × impact) | Mitigation | Rollback isolation |
| --- | --- | --- | --- |
| Task 1 | Status conflation falsely labels a sort action completed (medium × high) | Centralize the three-status lifecycle mapping; test `AVAILABLE_FROM_SNAPSHOT` as `UNKNOWN`; do not infer from events/array diffs. | Delete only the new output package and its unit test; no source pipeline behavior changed. |
| Task 1 | Highlight points outside a truncated capture (medium × high) | Gate highlights on captured `values().size()` and retain original pointer status. | Same as above. |
| Task 2 | A controller recomputes JDI/trace data or breaks old constructors (low × high) | Store `enrichedSteps` once, pass it to both mapper and response; keep two- and three-argument constructors and compact-constructor fallback. | Revert only Task 2-owned response/controller/tests/docs; Task 1 remains independently unit-testable. |
| Task 2 | Real JVM attach/launch fails or a class is not loaded (medium × high) | Reuse the existing compiler/JDI integration harness and timeouts; do not touch `JdiSession`/breakpoint setup. Class-load failures are reported as test/environment failures, not patched in normalization. | Remove only Task 2's integration tests or restore prior response wiring; existing JDI implementation is untouched. |
| Task 2 | A repeated scope/state causes an incorrect merge (medium × medium) | No joins by variable name and no carry-forward; frames own their observation and state IDs stay intact. | Same Task 2 rollback. |
| Task 3 | Formatting/build failure obscures a pre-existing failure (medium × medium) | Run targeted tests before full Maven commands; record the first failing command and classify it from its diff/stack trace. | No source changes in Task 3. |

### Task 1: Normalization records and pure mapper (RED → GREEN)

**Depends on:** no implementation task; use only existing model records.

**Owns files:**

- Create: `backend/src/main/java/com/example/astchunker/visualization/output/NormalizedVisualizationResponse.java`
- Create: `backend/src/main/java/com/example/astchunker/visualization/output/VisualizationOutputMapper.java`
- Create: `backend/src/test/java/com/example/astchunker/visualization/output/VisualizationOutputMapperTest.java`

**Consumes:** `List<ExecutionObservation>` produced by `VisualTraceBuilder.build(...)`; `List<AlgorithmHint>`; existing immutable visual DTOs.

**Produces:** `public NormalizedVisualizationResponse map(List<ExecutionObservation> steps, List<AlgorithmHint> hints)` for Task 2; callers construct a fresh stateless `VisualizationOutputMapper` directly (no Spring bean).

- [x] Write failing `VisualizationOutputMapperTest` cases using hand-built observations/hints: metadata ID `hint-0`, source mapping, input-order frames including a state-less frame, multiple states in one frame, and verbatim `VisualEvent` changes.
- [x] Run `mvn -f backend/pom.xml -Dtest=VisualizationOutputMapperTest test`; expect compilation/test failure because the output package does not exist.
- [x] Add `NormalizedVisualizationResponse` with the exact nested contract above. In compact constructors make insertion-ordered immutable snapshots which tolerate legitimate null nested values, while preserving existing immutable visual records.
- [x] Add the stateless instance mapper. Map hint metadata in list index order; map each observation independently; verify a state `hintIndex` resolves to its same-index hint before assigning `hint-<index>` (otherwise retain no fabricated join); map existing DTO references without changing contents; create `Frame` and `Change` in source order.
- [x] Extend the failing tests for sort slots: comparison + pending + completed must become three ordered operations when simultaneously present; assert the explicit comparison fields (`firstIndex`, `secondIndex`, `firstValue`, `secondValue`, `swapRequired`, `action`, `actionRequired`), mutation fields (`fromIndex`, `toIndex`, `value`), exact `kind`, lifecycle, original status, provenance (`SortFrame`, field, sequence, phase, snapshot phase), and preserved sorted region. Assert comparison kind is `COMPARISON`, `AVAILABLE_FROM_SNAPSHOT` lifecycle is `UNKNOWN`, exact pending/observed statuses map to `PENDING`/`OBSERVED`, and another status maps to `UNKNOWN`.
- [x] Extend tests for non-sort and data integrity: operations are empty; null array values remain null; missing/unsupported fields remain absent/null rather than zero; every existing pointer status is preserved; truncated actual length remains separate from captured values; `OUT_OF_BOUNDS`/`NOT_CAPTURED` pointers and every unavailable/uncaptured index generate no highlight; valid captured pointer/comparison/mutation indices generate the compact highlights; empty steps/hints serialize to v1.0 empty lists.
- [x] Add deterministic/immutability fixtures: same variable role names under different hint indices produce distinct algorithm IDs; an invalid state hint index does not join any other algorithm; later frames do not inherit missing values; mutating source collections after mapping cannot alter normalized role maps, lists, or nested null-bearing JSON-like values.
- [x] Run `mvn -f backend/pom.xml -Dtest=VisualizationOutputMapperTest test`; expect all mapper assertions to pass.
- [x] Run actual three-file Spotless apply/check using `mvn -f backend/pom.xml spotless:apply "-DspotlessFiles=.*visualization.output.*[.]java"` and the corresponding `spotless:check`; verify selected counts, not just build success. The previous comma-list syntax selected zero files and has been superseded.

**Task 1 measurable success:** The unit suite proves every output frame equals one input observation in order, preserves every event/state source status, emits no inferred operations for all three non-sort types (including both window variants), and exercises every lifecycle branch.

### Task 2: Additive response wiring, real-runtime contract coverage, and frontend documentation (RED → GREEN)

**Depends on:** Task 1's `NormalizedVisualizationResponse` and `new VisualizationOutputMapper().map(...)`.

**Owns files:**

- Modify: `backend/src/main/java/com/example/astchunker/dto/DebugStepsResponse.java:8-21`
- Modify: `backend/src/main/java/com/example/astchunker/controller/DebugController.java:58-68`
- Modify: `backend/src/test/java/com/example/astchunker/dto/DebugStepsResponseTest.java:14-58`
- Modify: `backend/src/test/java/com/example/astchunker/controller/MultipartApiIntegrationTest.java:51-82`
- Create: `backend/src/test/java/com/example/astchunker/visualization/output/VisualizationOutputIntegrationTest.java`
- Create: `docs/visualization-output-v1.md`

**Consumes:** enriched `steps` from the existing `VisualTraceBuilder`, warning/hint lists, and Task 1 mapper output.

**Produces:** `DebugStepsResponse(..., NormalizedVisualizationResponse visualization)` serialized as top-level `visualization`; compatible `DebugStepsResponse(steps,warnings)` and `DebugStepsResponse(steps,warnings,hints)` overloads.

- [x] Write failing response tests asserting the existing two- and three-argument constructors still preserve/copy legacy fields and construct `visualization` from their supplied steps/hints when it is null; assert an explicit visualization is kept unchanged; assert Jackson includes `visualization.schemaVersion`, algorithms, frames, source, states, operations, highlights, and changes while retaining `steps`, `warnings`, and `algorithmHints`.
- [x] Run `mvn -f backend/pom.xml -Dtest=DebugStepsResponseTest test`; expect failure because the response has no visualization component/constructor.
- [x] Change `DebugStepsResponse` to a four-component record whose final component is `NormalizedVisualizationResponse visualization`. Keep its legacy two- and three-argument constructors; both delegate with `null`. In the compact constructor first freeze `steps`, `warnings`, and `algorithmHints`, then assign `visualization = visualization == null ? new VisualizationOutputMapper().map(steps, algorithmHints) : visualization`.
- [x] Change `DebugController.debugSteps` to assign the single `visualTraceBuilder.build(...)` result to `enrichedSteps`, create one mapper result from that same list and hints, then construct the four-argument response. Do not rerun compilation, JDI, trace building, or analysis.
- [x] Add failing MockMvc assertions to the existing grouped-steps test: the prior legacy JSON paths remain valid and an empty-algorithm request returns `visualization.schemaVersion == "1.0"`, empty algorithms, and exactly as many frames as `steps`. Add a detected binary-search request assertion for metadata ID, state/source fields, empty operations, and unchanged existing state/event fields.
- [x] Create `VisualizationOutputIntegrationTest` with a shared real-JVM harness equivalent to the current AST → compiler → JDI → trace pipeline, then call the mapper. Add isolated source programs and assertions for: bubble ascending/descending; selection ascending/descending; insertion ascending/descending; binary search; two pointers; fixed sliding window; and variable sliding window. Sorting cases must assert original pending/observed statuses and lifecycles without treating comparisons as completed; non-sort cases must assert `operations` is empty and available pointers/scalars/ranges/events remain observable.
- [x] Add real-JVM fixtures with repeated method invocations and multiple detected hints; assert their frames remain ordered and their states retain distinct response-local algorithm IDs without name-based merging.
- [x] Run `mvn -f backend/pom.xml -Dtest=DebugStepsResponseTest,MultipartApiIntegrationTest,VisualizationOutputIntegrationTest test`; expect all response, HTTP, and real-JVM tests to pass.
- [x] Write `docs/visualization-output-v1.md`: endpoint/request scope, versioned response/field definitions, array/pointer status rules, operation lifecycle and snapshot semantics, sequence/timeline behavior, multiple states, limitations, compatibility and frontend playback instructions. Include compact, explicitly illustrative/test-derived Bubble Sort, Binary Search, and Sliding Window JSON samples that match the actual records (including `UNKNOWN` comparison lifecycle and empty non-sort operations).
- [x] Run targeted Spotless apply/check with separate safe regex filters for each response/controller/test filename and `.*visualization.output.*[.]java` for the output package. The filter is regex, not a comma-list/glob; verify actual selected file counts and leave the 26-file global baseline untouched.

**Task 2 measurable success:** HTTP tests prove old top-level fields are byte-for-byte structurally present while `visualization` is additive; real JVM tests produce valid normalized frames for all six supported algorithms and both sort directions, with no false completed operation.

### Task 3: Verification and final review (no source edits)

**Depends on:** Task 2.

**Owns files:** none; do not edit code or documentation in this task.

- [x] Run the full test suite: `mvn -f backend/pom.xml test`. Record counts and classify any failure as introduced or pre-existing using the failing test and diff.
- [x] Run the production verification: `mvn -f backend/pom.xml clean package`. Expect `BUILD SUCCESS`.
- [x] Re-run the exact Task 1/2 `-DspotlessFiles` checks, not repository-wide `spotless:check`; expect success and disclose the known 26 unrelated global baseline violations. If a targeted file fails, return to its owning task rather than formatting unrelated files.
- [x] Inspect `git diff --check` and `git diff -- backend/src/main/java/com/example/astchunker/algorithm backend/src/main/java/com/example/astchunker/visualization/sort`; expect no whitespace errors and no detector/sort-strategy changes. Review the final changed-file list against Task 1/2 ownership.
- [x] Confirm the handoff criteria: schema version is exactly `1.0`; every input observation has exactly one ordered frame; all six algorithms have integration coverage; legacy constructors/endpoints remain compatible; no extra dependency, detector, JDI, or sort-strategy change exists; and documentation describes only implemented fields.

**Task 3 measurable success:** full test and clean package succeed, both targeted Spotless checks succeed, the 26-file global formatting baseline is accurately disclosed rather than modified, `git diff --check` has no output, and the final diff is confined to Task 1/2 ownership.

## Review Focus

- A comparison with `AVAILABLE_FROM_SNAPSHOT` must render operands/action but retain lifecycle `UNKNOWN`; Task 1 synthetic sort test pins this.
- A pointer valid against actual array length but outside a truncated captured prefix must have no highlight; Task 1 truncated-pointer test pins this.
- `STATE_LEFT` and other visual events must remain changes rather than operations; Task 1 event test pins this.
- Repeated calls/scopes with equal variable names must stay separate frames/states, not merge by name; Task 2 real-JVM repeated-iteration coverage pins this.
- A response created through the legacy constructor must still include a valid normalized visualization while its pre-existing fields preserve their values; Task 2 response serialization test pins this.

## Plan Self-Review

- Spec coverage: all six algorithms, event normalization, sort operation fidelity, partial data, additive compatibility, REST serialization, real JVM integration, documentation, and final build/format verification are assigned.
- Type consistency: Task 1 defines the only new public mapper signature and nested DTO names; Task 2 consumes those exact names.
- Scope: no detector, sort strategy, JDI session, UI, dependency, or API-framework work is included.
- Rollback: Tasks 1 and 2 have disjoint writable ownership; Task 3 is read/verification only.
