# Activity Selection Semantic Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize and visualize Java Activity Selection across equivalent source structures and both `int[][]` and `List<Interval>` inputs without claiming optimality from a partial proof.

**Architecture:** A greedy-specific AST detector proves interval roles, full traversal, selection effects, and ordering separately. JDI projects only the two approved fields of list elements; a trace builder joins observations by invocation and input identity and reports pending, confirmed, or unknown decisions. Additive hint/frame fields keep existing sort and `/api/debug` consumers working.

**Tech Stack:** Java 17, JavaParser 3.28.2, JDI, Spring Boot 3.3.4, JUnit 5, vanilla JavaScript.

**Spec:** `docs/superpowers/specs/2026-10-19-activity-selection-design.md`

## Global Constraints

- Implement on `codex/semantic-sort-foundation` at `b791739` or its descendant; the `main` checkout lacks the latest sort architecture. The spec and this plan are review copies in the primary checkout because the managed worktree is currently read-only.
- Support `int[][]` with two inferred distinct constant columns and `List<Interval>` with same-source `record` or immutable `int` fields; allow `ArrayList` and `LinkedList` at runtime.
- Support `for`, `while`, and enhanced `for`, selected list or count, immutable local aliases, reversed comparison syntax, and resolvable non-overrideable same-class helpers.
- Preserve `>=` (half-open) and `>` (closed) as distinct policies. Accept negative time, equal endpoints, duplicate intervals, empty and singleton input.
- `activity-selection` means selection-rule proof. `SOURCE_PROVEN`, `INPUT_PRECONDITION`, runtime verification, and optimality claims must be separate.
- Keep existing constructors and JSON fields of `AlgorithmHint`, `VisualState`, and `ObservationResult`, and preserve `/api/debug` without a projection hint.
- Do not call target-VM getters or `toString()`. Bound field projection by the existing element/depth budgets. Reject ambiguous effects; use `UNKNOWN` or `TRUNCATED` for incomplete observations.
- No new dependency or unrelated refactor. Do not commit or push unless the user explicitly asks; this overrides the skill's default commit steps.

## Review Focus

1. Two distinct lists with equal interval content must never share a trace; pin by JDI object ID in Task 4 and Task 5.
2. Repeated references to the same `Interval` object in one list must remain distinct candidate positions; pin in Task 4 and Task 5.
3. A null list element or short `int[][]` row must yield unknown candidate data, never a fabricated decision; pin in Task 4 and Task 5.
4. `Integer.MIN_VALUE`, zero-length intervals, and exact-boundary contact must retain the chosen overlap policy; pin in Task 2 and Task 5.
5. A subtraction comparator can overflow and must not receive `SOURCE_PROVEN`; pin in Task 3.

## File Map

- `backend/src/main/java/com/example/astchunker/algorithm/greedy/IntervalRoles.java`: normalize the two interval representations and give stable source-role IDs.
- `backend/src/main/java/com/example/astchunker/algorithm/greedy/ActivitySelectionDetector.java`: prove traversal, guard, update, and output effects; return a semantic hint.
- `backend/src/main/java/com/example/astchunker/algorithm/greedy/EndOrderingAnalyzer.java`: classify end-time ordering independently of selection.
- `backend/src/main/java/com/example/astchunker/model/ActivitySelectionPattern.java`: versioned metadata, role IDs, operation IDs, policy, representation, and ordering evidence.
- `backend/src/main/java/com/example/astchunker/algorithm/AlgorithmPatternAnalyzer.java` and `backend/src/main/java/com/example/astchunker/model/AlgorithmHint.java`: integrate the new detector and nullable metadata.
- `backend/src/main/java/com/example/astchunker/mapping/IntervalValueProjection.java`, `VariableMapper.java`, `backend/src/main/java/com/example/astchunker/debug/JdiSession.java`, `backend/src/main/java/com/example/astchunker/model/ObservationResult.java`, `backend/src/main/java/com/example/astchunker/controller/DebugController.java`: pass proven projection into JDI, capture object identity, and keep old overloads.
- `backend/src/main/java/com/example/astchunker/visualization/greedy/ActivitySelectionTraceBuilder.java`, `backend/src/main/java/com/example/astchunker/model/ActivitySelectionFrame.java`, `backend/src/main/java/com/example/astchunker/visualization/VisualTraceBuilder.java`, `backend/src/main/java/com/example/astchunker/model/VisualState.java`: construct confirmed step frames.
- `UI/dashboardPage.html`, `backend/examples/greedy/ActivitySelectionArraySample.java`, `backend/examples/greedy/ActivitySelectionListSample.java`, `docs/activity-selection-test-guide.md`, `README.md`: render and document user-visible behavior.
- Tests mirror these packages: `algorithm/greedy/IntervalRolesTest.java`, `ActivitySelectionDetectorTest.java`, `EndOrderingAnalyzerTest.java`; `mapping/VariableMapperTest.java`; `debug/JdiSessionIntegrationTest.java`; `visualization/greedy/ActivitySelectionTraceBuilderTest.java`, `visualization/VisualTraceIntegrationTest.java`; and controller API tests.

### Task 1: Normalize interval roles

**Files:** Create `algorithm/greedy/IntervalRoles.java` and `algorithm/greedy/IntervalRolesTest.java`.

**Interfaces:** `static Optional<IntervalRoles> resolve(CompilationUnit unit, Node loop)`; immutable result exposes `representation`, `inputDeclarationId`, `candidateDeclarationId`, and either `startColumn`/`endColumn` or `intervalType`/`startField`/`endField`. All later tasks consume these role names; no guessed field names or column positions.

- [ ] Write tests for swapped array columns, arbitrary variable names, `record` accessors, final-class direct fields/getters, and immutable local aliases. Assert exact IDs and role mapping.
- [ ] Write negative tests for two reads from different inputs, same column for both roles, dynamic column indices, mutable/reassigned fields or aliases, unrelated getters, unresolved/overridable helper calls, and unsupported element types. Assert empty result.
- [ ] Run `cd backend && mvn -q -Dtest=IntervalRolesTest test`; confirm the new test fails for the missing resolver.
- [ ] Implement `resolve` with JavaParser declaration and call resolution; allow only side-effect-free, bounded same-source expressions. Keep representation-specific reads inside this class.
- [ ] Rerun the focused test; require all cases to pass.

### Task 2: Prove selection semantics and publish a hint

**Files:** Create `algorithm/greedy/ActivitySelectionDetector.java`, `model/ActivitySelectionPattern.java`, `algorithm/greedy/ActivitySelectionDetectorTest.java`; modify `AlgorithmPatternAnalyzer.java`, `AlgorithmHint.java`, and `AlgorithmPatternAnalyzerTest.java`.

**Interfaces:** `Optional<AlgorithmHint> detect(CompilationUnit unit, Node loop)` consumes `IntervalRoles.resolve`. `ActivitySelectionPattern` is nullable on `AlgorithmHint`; fields include version `1`, representation, overlap policy (`HALF_OPEN`/`CLOSED`), declaration/operation IDs, and ordering evidence populated by Task 3. Preserve all existing `AlgorithmHint` constructors via delegation.

- [ ] Write positive corpus tests for `for`, `while`, enhanced `for`, count and list outputs, first-candidate branch, reversed `>=`/`>` syntax, local aliases, duplicate/negative endpoints, and a fixture combining several variations. Assert one `activity-selection` hint and the exact overlap policy, roles, and operation IDs. Include `Integer.MIN_VALUE` and zero-length boundary cases.
- [ ] Write near-negative tests: wrong comparison, update with `start` or another candidate's `end`, changed rejected branch, skipped candidate, early `break`/`return`, input mutation, alias reassignment, uncertain helper dispatch. Assert no activity hint. A missing sort alone remains eligible for a hint with `INPUT_PRECONDITION` later.
- [ ] Run `cd backend && mvn -q -Dtest=ActivitySelectionDetectorTest,AlgorithmPatternAnalyzerTest test`; confirm the new cases fail before implementation.
- [ ] Implement full-scan and exact-effect proof. Handle `ForEachStmt` in a dedicated Activity Selection path; do not feed it to legacy search heuristics. Bind the hint to the owning callable and source IDs using the existing analyzer conventions.
- [ ] Rerun focused tests and existing analyzer tests; require pass and no new hint for the negative corpus.

### Task 3: Classify end ordering independently

**Files:** Create `algorithm/greedy/EndOrderingAnalyzer.java` and `algorithm/greedy/EndOrderingAnalyzerTest.java`; update `ActivitySelectionDetector.java` and `ActivitySelectionPattern.java`.

**Interfaces:** `EndOrderingAnalyzer.classify(CompilationUnit unit, Node loop, IntervalRoles roles)` returns `SOURCE_PROVEN`, `INPUT_PRECONDITION`, or `CONTRADICTED` plus nullable sort AST ID. Task 2's detector attaches this result; a contradicted ordering must never be labeled proven or optimal.

- [ ] Write tests for `Arrays.sort` on `int[][]`, `List.sort`, and `Collections.sort` on the same list using `Comparator.comparingInt`, `Integer.compare`, and equivalent pure lambda forms. Assert `SOURCE_PROVEN` and sort ID only when the full-input sort precedes the scan and no later write invalidates it.
- [ ] Write tests for no visible sort (`INPUT_PRECONDITION`), sorting by `start`, descending `end`, different input, partial sort, intervening write, impure/unresolved comparator, and `a.end - b.end` overflow risk. Assert no false `SOURCE_PROVEN`; reject contradictory ordering when its direction is known.
- [ ] Run `cd backend && mvn -q -Dtest=EndOrderingAnalyzerTest,ActivitySelectionDetectorTest test`; confirm new cases fail.
- [ ] Implement conservative comparator direction/effect proof and source-order checks. Do not execute user comparator code.
- [ ] Rerun focused tests; require pass.

### Task 4: Project list values and preserve runtime identity

**Files:** Create `mapping/IntervalValueProjection.java`; modify `VariableMapper.java`, `JdiSession.java`, `ObservationResult.java`, `DebugController.java`, `VariableMapperTest.java`, `JdiSessionIntegrationTest.java`; add or extend controller API tests.

**Interfaces:** `IntervalValueProjection.fromHints(List<AlgorithmHint>)` contains only proven input/type/field IDs and field names. Add `JdiSession.observe(target, analysis, IntervalValueProjection)` and `VariableMapper.map(..., IntervalValueProjection)` overloads; existing signatures delegate to an empty projection. Add nullable, JSON-ignored `ObservationResult.objectId` while keeping `arrayObjectId` and old constructors.

- [ ] Write mapper and JDI tests for `ArrayList` and `LinkedList` containing record/final-class intervals. Assert ordered pairs of `int` values, input object ID, distinct element positions for repeated object references, and unchanged legacy snapshots without a projection.
- [ ] Test distinct equal-content lists, null element, wrong runtime type, unreadable field, and element/depth truncation. Assert missing data is `UNKNOWN`/`TRUNCATED` with no invented values; no invocation of target getters.
- [ ] Add API regression tests for unchanged `/api/debug` shape and old `ObservationResult` constructors; assert new internal IDs are absent from JSON.
- [ ] Run `cd backend && mvn -q -Dtest=VariableMapperTest,JdiSessionIntegrationTest test`; confirm new tests fail.
- [ ] Build projection from hints before calling JDI in `/api/debug/steps`; keep `/api/debug` on the empty-projection overload. Read only the approved two `int` fields, using existing budgets. Capture `ObjectReference.uniqueID()` for list identity.
- [ ] Rerun focused and controller tests; require pass.

### Task 5: Confirm decisions from observations

**Files:** Create `visualization/greedy/ActivitySelectionTraceBuilder.java`, `model/ActivitySelectionFrame.java`, `visualization/greedy/ActivitySelectionTraceBuilderTest.java`; modify `VisualTraceBuilder.java`, `VisualState.java`, `VisualTraceIntegrationTest.java`.

**Interfaces:** `ActivitySelectionTraceBuilder(List<AlgorithmHint> hints, Map<String, Node> nodes).states(ExecutionObservation step, Node point, String mainClassName)` returns activity `VisualState`s with nullable `activitySelectionFrame`. The frame includes candidate position, `start`, `end`, `lastEnd`, overlap policy, decision, pending/observed effect, selected count/list, ordering evidence, runtime order/overlap/output statuses. Existing frame constructors delegate with null activity frame.

- [ ] Test `PENDING` at `BEFORE_LOCATION`, followed by `OBSERVED_AFTER_WRITE` only after a later snapshot proves the same candidate's write on the same thread, invocation, and input object ID. Test count and list output separately.
- [ ] Test two equal-content inputs with different IDs, repeated interval object in one input, nested/multiple calls, null/short row, missing observation, and truncated list. Assert no cross-run confirmation and `UNKNOWN`/`TRUNCATED` where evidence is incomplete.
- [ ] Test empty/singleton, negative time, `Integer.MIN_VALUE`, duplicate and zero-length intervals, and exact contact under both policies. Compare observed selected output to an independent brute-force optimality oracle for small fixtures. Assert `VIOLATED` on observed out-of-order end, overlap, or result mismatch.
- [ ] Run `cd backend && mvn -q -Dtest=ActivitySelectionTraceBuilderTest,VisualTraceIntegrationTest test`; confirm new cases fail.
- [ ] Implement per-invocation/input trace state, pending-to-confirmed transition, and separate `SOURCE_PROVEN`/`RUNTIME_VERIFIED`/`UNKNOWN` evidence. Claim optimality only when full runtime selection/output proof and a valid ordering proof coexist.
- [ ] Rerun focused tests; require pass.

### Task 6: Expose and document the feature

**Files:** Modify `UI/dashboardPage.html`, `README.md`; create both `backend/examples/greedy/ActivitySelection*Sample.java` fixtures and `docs/activity-selection-test-guide.md`; extend controller and `VisualTraceIntegrationTest.java` tests.

**Interfaces:** `/api/debug/steps` adds nullable `algorithmHints[].activitySelection` and `steps[].visualStates[].activitySelectionFrame`; existing fields and `/api/debug` stay compatible. UI reads these fields defensively and uses the generic variable view when absent.

- [ ] Write API tests asserting additive metadata/frame JSON and absence of a false optimality label for `INPUT_PRECONDITION`, incomplete trace, or contradiction. Include one source fixture with several supported syntax variations.
- [ ] Run the focused API test; confirm it fails before the UI/API wiring is complete.
- [ ] Add a view showing candidate order, boundary policy, current `lastEnd`, accept/skip decision, selected output, source-order evidence, and observed-runtime status. Escape source-derived text with the existing UI helper and tolerate null/missing frames.
- [ ] Add compile-ready array/list examples and document supported patterns, unsupported cases, evidence labels, and upload steps.
- [ ] Run focused tests, then `cd backend && mvn -q test` and `cd backend && mvn -q spotless:check`. Run `git diff --check`; extract the inline UI script and run `node --check` on the extracted file if Node is available. Record exact results; use a browser smoke test only if a permitted browser surface is available.
- [ ] Review the final diff for existing user changes, old API behavior, false `SOURCE_PROVEN`/optimality paths, and accidental secrets. Leave the branch uncommitted and unpushed until explicitly requested.
