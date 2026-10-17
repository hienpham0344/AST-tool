# Unified Visualization UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make the standalone dashboard render normalized visualization `1.0` for all six supported algorithms while preserving the existing upload, raw-variable table, source view, export, and playback workflow.

**Architecture:** Add one dependency-free browser/CommonJS module that turns a response into a input-ordered frame timeline and safe HTML cards. The dashboard becomes a thin adapter: it chooses normalized v1 only when the version is exactly supported, otherwise delegates to its current legacy renderer or shows an explicit unsupported-version message. Snapshots, rather than inferred transitions, remain the display source of truth.

**Tech stack:** Existing standalone HTML/CSS/JavaScript, browser globals, Node built-in `node:test`/`assert`; no framework, package, backend, or dependency change.

**Spec:** User-approved short UI design (chat, 2026-10-12; `-auto`) plus `docs/visualization-output-v1.md`. The approved design keeps the current layout and uses a single selector with All/one algorithm, direct normalized rendering, legacy fallback only when visualization is absent, and an explicit unsupported-version message.

## Global constraints

- Scope is UI-only: do not edit backend, API contracts, build/test configuration, dependencies, or the existing uploaded-file/export behavior.
- Support only `visualization.schemaVersion === "1.0"`; an absent visualization uses the retained legacy visual-state renderer, while any present unknown version renders a clear unsupported-version message and is never parsed as v1.
- Frame array order is playback order. `sequence` is a response key, not an array index; join raw `steps` to frames by `sequence`, never by position.
- Join metadata through `state.algorithmId` only. Do not recover identity by variable name, source line, hint index, or first state.
- Render every selected state independently. Selector defaults to **All**; All renders multiple active cards, while choosing one ID filters the cards.
- Preserve exact null, empty, missing, unavailable, invalid, out-of-bounds, and truncated data. Never substitute `0`, carry values forward, fabricate a cell/marker/range, infer completion/swap/comparison, or equate `READY`/verified-region status with algorithm completion.
- Display current-frame `changes` as snapshot/context text (including `stateId`); introduce no transition animation.
- All interpolated response-derived text, labels, status, values, and source metadata must pass through `safeHtml` before becoming HTML.
- Documentation test-guide changes are owned by the parent task and are deliberately outside this plan's writable files. No commit or push.
- User mode is auto: no approval pauses for routine stages, but any formal tester/reviewer finding must be reported to the user before fixing. Expected TDD RED failures belong to implementation, not a final QA defect. Do not delete/replace entire existing files, alter JDI/ObservationResult, or add dependencies.
- Work in the current dedicated branch because the UI depends on approved uncommitted backend output. Preserve those files byte-for-byte; no worktree copy, branch switch, commit, push or broad formatting.

## Scope check

- **Already present:** `UI/dashboardPage.html` has upload, runtime request, source display, raw variable table, export, timeline controls, and a legacy sort-only renderer based on `steps[].visualStates[].sortFrame`.
- **Minimum change:** a pure normalized-timeline/render helper, its Node tests, and a small dashboard adapter/script include; retain the legacy renderer as fallback.
- **Complexity limit:** three implementation files, one implementation task, and no new renderer hierarchy or UI framework. The test guide is a fourth implementation-adjacent document owned by the parent; this plan file is the fifth scoped file.

## Review focus

- Reordered/non-contiguous sequences: source and raw variables must come from the current frame's own sequence, never a coincidentally matching array index.
- Present malformed/future visualization: show a clear message without treating it as legacy or parsing future frames; absent visualization retains the actual legacy path.
- Partial captures and bad statuses: null remains null, actual length stays separate, and invalid/uncaptured pointer or unknown range does not create drawable evidence.
- Multiple/inactive states and new uploads: All preserves every active card; filtering or loading a new response never leaves stale cards, selection or a playback timer.
- Untrusted text and operation evidence: escape every dynamic HTML field and retain UNKNOWN versus PENDING/OBSERVED without deriving a swap or completion from changes.

## Data flow

```text
uploaded .java
  -> AstAnalyzer: AST node IDs and algorithm hints
  -> BreakpointMapper: eligible source locations
  -> JdiSession: per-thread breakpoint snapshots
  -> VariableMapper: scope-correct runtime variables
  -> backend response: steps + visualization { algorithms, frames }
  -> responseLookup(response): sequence-keyed raw step lookup
  -> createTimeline(response): ordered frames + algorithmId metadata join
  -> dashboard current sequence + selected algorithm ID
  -> renderFrame(...): normalized cards, frame changes/context, normalized source
  -> retained variable table: raw variables from responseLookup(sequence)
  -> renderSourceViewer: normalized frame.source (or legacy step source fallback)
```

No UI code retries, attaches to, launches, or configures JDI. It only renders the response already returned by the existing upload request.

## Dependency graph and ownership

```text
Task 1 — normalized helper, Node RED/GREEN tests, dashboard adapter (three UI files)
  -> Task 2 — verification and independent review (no files)
```

| Phase | Exclusive file ownership | Prerequisite | Output |
| --- | --- | --- |
| Task 1 | Create `UI/unified-visualization.js`; create `UI/tests/unified-visualization.test.cjs`; modify `UI/dashboardPage.html` | Current response contract | Tested v1 rendering and retained legacy playback adapter |
| Parent documentation | `docs/unified-visualization-ui-guide.md` | Task 1 behavior | Manual-upload instructions; not edited by this plan |
| Task 2 | None | Task 1 | Evidence only; no source edits |

Task 2 cannot alter Task 1 files. The parent documentation owner must not modify the three Task 1 files.

## Public helper contract

`UI/unified-visualization.js` must be an IIFE/factory that exposes the same frozen API as `globalThis.UnifiedVisualization` in a browser and `module.exports` under CommonJS:

```js
createTimeline(response) // -> { mode, message, frames, algorithmsById, rawStepsBySequence }
responseLookup(response) // -> Map<sequence, rawStep>; first matching sequence wins, never positional lookup
renderSelector(timeline, selectedAlgorithmId) // -> safe HTML for All + one algorithm selector
renderFrame(timeline, sequence, selectedAlgorithmId) // -> { html, source, rawStep, stateCount }
safeHtml(value) // -> escaped display string
```

- `mode` is `normalized`, `legacy`, or `unsupported`. Legacy/unsupported timelines use raw `steps` as their frames and never parse v1/future fields; unsupported shows a clear message instead of algorithm cards. Null/absent visualization is legacy; malformed present v1 data is an explicit error, not silent legacy fallback.
- `createTimeline` keeps `visualization.frames` order exactly, indexes `visualization.algorithms` by each algorithm's `id`, and joins a state's `algorithmId` to that map. It does not sort, resequence or merge states.
- `renderFrame` finds the requested frame by its `sequence`; its `source` is that normalized frame's `source`. `rawStep` comes from `rawStepsBySequence`, so the dashboard can retain the existing raw-variable table and legacy source fallback.
- Sequence lookup uses the same string key for numeric/string representations (for example `9` and `"9"`), never a positional fallback. For legacy/unsupported mode, source is derived only from that raw step; normalized source never borrows raw-step bounds.
- Supplied cell highlights are clipped to both captured values and known actual length. Pointer markers also require the corresponding current pointer to be VALID. Candidate ranges and verified regions must obey their source statuses and captured bounds.
- In normalized mode, `renderSelector` exposes All as the default (`selectedAlgorithmId === null`), plus one option per algorithm ID. `renderFrame` renders all current states for All, or only states matching the selected ID. Unjoined states get an honest unknown card in All; no arbitrary metadata is assigned.
- The card renderer is generic: it presents algorithm type/plan/status, array capture/truncation, pointer roles/statuses, scalar roles/statuses, range, operations, highlights, sorted-region evidence, notes, and snapshot phase for every v1 state. It may use operation/highlight kinds for CSS labels, but must not contain algorithm-specific inference branches.

## Test matrix

| Module/behavior | Node unit test (pure helper/render) | Real backend/JVM browser check |
| --- | --- | --- |
| Response selection/version handling | absent visualization gives legacy; `1.0` gives normalized; future/unknown version gives unsupported text without v1 cards | Upload a response without visualization and a current v1 response; verify respective fallback/normalized UI |
| Timeline and joins | frame input order, non-contiguous/reordered sequences, multi-state/no-state frames, metadata joins only by `algorithmId`, raw lookup by sequence | Repeated source lines/invocations: slider order remains response order; variables track matching sequence |
| Generic state fidelity | literal fixtures for the six types: Bubble, Selection, Insertion, Binary Search, Two Pointers, and Sliding Window (both fixed- and variable-window cases); array/pointer/range/scalar display | Upload one real sample for each supported family/type and inspect cards |
| Partial/unsafe data | null element, empty array, truncated capture, missing/bad pointer/range/scalar status, no phantom marker/cell/zero/completion text | Real truncated/missing-data response if supplied by backend; otherwise inspect Network JSON against rendered status |
| Operations/highlights/changes | simultaneous comparison + pending + completed operations in slot order; `UNKNOWN` is literal; changes include every current event/stateId/context; malicious strings escaped | Sort response verifies pending and observed wording is distinct and no animation/replay is implied |
| Dashboard controls | Pure timeline/selector/empty/inactive rendering behavior; timer/DOM behavior is verified in the real browser rather than a mock DOM | Exercise Previous/Next/Play/slider/speed/reset/new-file controls after actual uploads |

## Risks and rollback

| Phase | Risk (likelihood × impact) | Mitigation | Isolated rollback |
| --- | --- | --- | --- |
| Task 1 | Schema/sequence join selects variables or source from another snapshot (medium × high) | One `responseLookup` test with reordered/non-contiguous sequences; no index lookup exists in the helper API. | Remove dashboard normalized branch/script include and new helper/test; the retained legacy renderer remains intact. |
| Task 1 | UI falsely implies a completed sort, valid marker, or full array from partial data (medium × high) | Literal null/truncated/bad-status/UNKNOWN tests; status-driven labels and captured-prefix bounds; render operations independently. | Same rollback; no response or JDI state is mutated. |
| Task 1 | XSS through source/runtime labels (medium × high) | Test malicious labels and route every dynamic card/selector/context field through `safeHtml`. | Revert only Task 1 UI files. |
| Task 2 | JDI attach/launch failure, class not prepared, or breakpoint scope error prevents real-upload validation (medium × high) | Do not touch JDI/AST code; run existing backend with known samples, record the backend error verbatim, and classify it as environment/backend evidence rather than patching UI. | Task 2 changes no files; Task 1 is fully covered by deterministic Node fixtures. |
| Task 2 | Playback reset leaks a timer or stale selection into a new run (medium × medium) | Manual reset/new-file test plus Node-level empty/no-active-state tests; reuse the dashboard's stop/reset lifecycle. | Revert only adapter wiring; existing controls resume legacy behavior. |

## Task 1: Pure normalized visualization and dashboard integration (RED → GREEN)

**Depends on:** existing v1 response contract and existing legacy dashboard renderer.

**Files:**

- Create: `UI/unified-visualization.js`
- Create: `UI/tests/unified-visualization.test.cjs`
- Modify: `UI/dashboardPage.html`

**Consumes:** the POST runtime response's `steps` and optional `visualization`; current playback index/timer and source/variable-table hooks.

**Produces:** the exact global/CommonJS helper contract above; a dashboard that treats normalized frames as primary when v1 is present and legacy rendering as fallback only when visualization is absent.

- [x] Write failing `node:test` fixtures using literal data for all six types: `bubble-sort`, `selection-sort`, `insertion-sort`, `binary-search`, `two-pointers`, and `sliding-window`; give the last fixture both fixed- and variable-window cases. Assert generic array/pointer/range/scalar/status output, selector default All/individual selection cards, and no algorithm-specific rendering assumptions.
- [x] Add failing tests for frame array order versus reordered/non-contiguous `sequence`, multiple states and state-less frames, response metadata joined only by `algorithmId`, and raw variable/source lookup by sequence rather than matching array positions.
- [x] Add failing edge/security tests: null values remain visible as null; empty versus unavailable arrays differ; truncated length never creates missing cells; invalid/not-captured/out-of-bounds pointer or invalid/unknown range creates no phantom marker; bad scalar/status has no zero fallback; unsupported version emits a version message without parsing frames; absent visualization reports legacy mode; malicious labels are escaped.
- [x] Add failing operations/context tests: a state with comparison, pending mutation, and completed mutation renders all three in supplied order; lifecycle `UNKNOWN` stays unknown; current-frame changes retain every event with `stateId` and context; no change is rendered as a swap/completion/animation.
- [x] Run `node --test UI/tests/unified-visualization.test.cjs`; expect failure because the helper/module does not exist.
- [x] Implement `UI/unified-visualization.js` as the browser-global/CommonJS pure module. Create the frame timeline without sorting, build raw lookup by numeric/string sequence equality without a positional fallback, make metadata lookup exclusively `algorithmId`, and return legacy/unsupported modes exactly as defined above.
- [x] Implement generic, escaped card/selector HTML. Draw highlights only from supplied highlight entries that address currently captured cells; show unavailable/inactive statuses and labels instead of inventing cells, markers, ranges, comparisons, swaps, or completion. Include raw values and statuses in accessible text; display `changes` and context as a current snapshot statement only.
- [x] Run `node --test UI/tests/unified-visualization.test.cjs`; expect all specified behavior assertions to pass.
- [x] Add one ordinary script include for `unified-visualization.js` before the existing dashboard script. Adapt `renderExecutionSteps`, `updateExecutionStep`, source rendering, and reset/new-run paths to: initialize a timeline once per response; retain raw variables via `rawStep`; render normalized cards/selector/source when mode is normalized; call the existing legacy renderer only for legacy mode; and render the unsupported message without attempting future-schema fields.
- [x] Keep Previous/Next, Play, slider, speed, counter, source view, upload/reset, and export available for every response with steps, including no active algorithms or no current states. Stop playback and clear timeline/selection on a new selected file or runtime run; do not create a play timer for an empty dataset.
- [x] Re-run `node --test UI/tests/unified-visualization.test.cjs`; expect all tests to pass after dashboard-compatible helper integration.

**Measurable success:** one Node command passes all literal generic/fallback/partial-data/security tests; with a valid v1 response, the initial frame shows All selected and every current state card; variables resolve by matching sequence, source comes from `frame.source`, and each playback/reset control remains usable without an active algorithm.

## Task 2: Final QA and independent review (no source edits)

**Depends on:** Task 1 and the parent-owned update to the manual test guide.

**Files:** none. Do not modify Task 1 files during this phase; return defects to Task 1 for a new RED → GREEN cycle.

- [x] Run `node --test UI/tests/unified-visualization.test.cjs`; record the pass count.
- [x] After the final Maven build, start the existing backend without source/configuration changes and use the parent guide's Java uploads. Verify v1 cards for all six types, selector All/one algorithm, multi/no-state frames, normalized source, sequence-matched variables, partial-data messages and all playback/reset controls.
- [x] Verify absent visualization takes the actual legacy visual-state path, while a controlled unknown-version response presents only the clear unsupported-version message; check no errors in the browser console.
- [x] Run `mvn -f backend/pom.xml clean package`; expect `BUILD SUCCESS`. This is regression evidence only: neither backend source nor dependency state may change.
- [x] Run `git diff --check` and inspect this UI task's delta: only its three UI files, this plan and the parent guide are allowed. Previously approved uncommitted backend files remain present but must retain their baseline hashes; do not classify them as new UI-task changes.
- [x] Have an independent reviewer prove behavior from rendered UI/Node test outputs, not source-code grep: version fallback, sequence joins, escaping, simultaneous operations, truthful partial data, six-type coverage, and reset/control behavior.

**Measurable success:** Node tests and backend package pass; real browser uploads demonstrate all six types and both window forms; independent review is approved. No backend/source/dependency change belongs to this UI task.

## Plan self-review

- Data flow names the required AST/JDI modules, response transformations, normalized UI rendering, raw variable lookup, and normalized source mapping.
- The dependency graph has one implementation phase before QA; every phase owns disjoint files and the guide is explicitly parent-owned.
- Risks include JDI launch/attach, class preparation, breakpoint scope, stale playback, partial-data misrepresentation, and XSS, with high-impact mitigations and isolated rollback.
- The test matrix distinguishes pure Node unit coverage from real-JVM/browser integration, and the success criteria are command/behavior based.
- No file:line citations are used; all implementation references above were verified from the current dashboard and contract before writing this plan.
