# Semantic Two Pointers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize and visualize opposing and same-direction two-pointer patterns in Java 17 across equivalent source structures on `int[]` and `List<Integer>`.

**Architecture:** Dedicated AST role/progress analyzers prove movement, bounds and shared-data access before `TwoPointersDetector` emits a hint. A bounded JDI projection and trace builder join snapshots by invocation, thread and input object; additive metadata/frame fields preserve existing API consumers. Existing sort, Activity Selection, binary search and sliding-window precedence stays intact.

**Tech Stack:** Java 17, JavaParser 3.28.2, JDI, Spring Boot 3.3.4, JUnit 5, vanilla JavaScript.

**Spec:** `docs/superpowers/specs/2026-10-19-two-pointers-design.md`

## Global Constraints

- Work on `codex/semantic-sort-foundation`; preserve the untracked spec and any other user work. Do not commit or push this new work unless the user asks, per the user's AGENTS.md instructions.
- Keep type `two-pointers`, current endpoint names, all old JSON fields and public constructors. Add only nullable `AlgorithmHint.twoPointers` and `VisualState.twoPointersFrame`.
- `int[]`, `ArrayList<Integer>` and `LinkedList<Integer>` are the only runtime data types in this increment. Inspect JDI fields only; never invoke target-VM methods.
- A hint describes the two-pointer traversal pattern, not the correctness of pair-sum, palindrome, compaction or any particular answer. Missing proof yields no specialized hint; missing runtime data yields `UNKNOWN` or `TRUNCATED`.
- Preserve one hint per loop and precedence: sort, Activity Selection, binary search, sliding window, then two pointers. Use AST declaration IDs rather than names as identities.
- Bound list projection by `VariableMapper`'s current `MAX_ARRAY_ELEMENTS = 100`. Key trace state by thread, invocation ID and input object ID; never confirm a write before a later observation.
- No dependency, lockfile or unrelated formatting changes. Run tests with the local Maven/JDK setup described in Verification below.

## Review Focus

1. A shadowed local with the same name as a pointer must not inherit its declaration ID or get a hint (Task 1 test).
2. An unguarded `do/while` over an empty input must not receive an opposing-pointer hint (Task 2 test).
3. A `List<Integer>` implementation other than exact `ArrayList`/`LinkedList` must be `UNKNOWN`, even if it overrides `get` (Task 4 test).
4. Separate calls reusing the same input object must have separate pending/observed transitions (Task 5 test).
5. Multiple pointer writes at one breakpoint line must not be reconstructed as individually observed moves (Task 5 test).

## File Map

- `algorithm/twopointers/PointerRoleAnalyzer.java`: identify data, pointer declarations, access operations, aliases and guards; produce candidate roles.
- `algorithm/twopointers/PointerProgressAnalyzer.java`: prove progress on each continuing control-flow path, including helpers with a fixed safe target.
- `algorithm/twopointers/TwoPointersDetector.java`: combine role/progress proof and build the public hint.
- `model/TwoPointersPattern.java`: source proof metadata; `model/TwoPointersFrame.java`: observed movement and uncertainty.
- `mapping/IntegerListProjection.java`: select inputs that need bounded `Integer` field projection; `VariableMapper.java` performs field reads.
- `visualization/TwoPointersTraceBuilder.java`: join source proof with snapshots and emit states; `VisualTraceBuilder.java` coordinates it.
- `AlgorithmPatternAnalyzer.java`, `JdiSession.java`, `DebugController.java`, `AlgorithmHint.java`, `VisualState.java`, `UI/dashboardPage.html`: narrow integration changes.
- Matching tests under `backend/src/test/java`, samples under `backend/examples/twopointers`, usage guide under `docs/`.

### Task 1: Extract Pointer Roles and Progress Proof

**Files:** Create `backend/src/main/java/com/example/astchunker/algorithm/twopointers/PointerRoleAnalyzer.java`, `PointerProgressAnalyzer.java`, `backend/src/main/java/com/example/astchunker/model/TwoPointersPattern.java`; create matching `PointerRoleAnalyzerTest.java`, `PointerProgressAnalyzerTest.java` in the same test package.

**Interfaces:** `PointerRoleAnalyzer.candidates(CompilationUnit unit, Node loop): List<PointerRoleAnalyzer.Candidate>` returns data/access/pointer declaration IDs and normalized access operations. `PointerProgressAnalyzer.prove(Node loop, PointerRoleAnalyzer.Candidate candidate): Optional<PointerProgressAnalyzer.Proof>` returns `TwoPointersPattern.Movement`, guard and operation node IDs; neither class emits public hints. Candidate and Proof are immutable records with explicit fields, not untyped maps. Create `TwoPointersPattern(int schemaVersion, Movement movement, String representation, Map<String,String> declarationIds, Map<String,String> operationAstNodeIds, List<String> evidence)`; require schema version `1`, movement `OPPOSING`/`SAME_DIRECTION`, and representation `INT_ARRAY`/`INTEGER_LIST`.

- [ ] Write corpus tests for `array[i]`, statically typed `List<Integer>.get(i)`/`set(i, value)` including fully qualified `java.util.List<Integer>`, `for`/`while`/`do/while`, reversed comparisons, `++`/`+= 1`/`p = p + 1` and decreasing equivalents. Assert declaration IDs and normalized motion, including an immutable alias and a resolvable private/static/final same-class helper.
- [ ] Write negative tests for shadowed names, reassigned alias, two collections, unsupported `List` static type, ambiguous/overrideable helper, side effect, wrong-direction update and a continuing path with no progress. Assert no candidate/proof rather than a lower confidence score.
- [ ] From `backend/`, run `mvn -q -Dtest=PointerRoleAnalyzerTest,PointerProgressAnalyzerTest test`; require failing tests from missing classes or behavior.
- [ ] Implement role lookup through `LocalVariableBindings`, exact supported access forms and conservative alias/helper resolution in `PointerRoleAnalyzer`; implement guard/movement path checks in `PointerProgressAnalyzer`. Reject unresolved paths and nested callable bodies.
- [ ] Rerun `mvn -q -Dtest=PointerRoleAnalyzerTest,PointerProgressAnalyzerTest test`; require pass.

### Task 2: Recognize Opposing Pointers

**Files:** Create `backend/src/main/java/com/example/astchunker/algorithm/twopointers/TwoPointersDetector.java`; modify `AlgorithmPatternAnalyzer.java`, `AlgorithmHint.java`; create `backend/src/test/java/com/example/astchunker/algorithm/twopointers/TwoPointersDetectorTest.java`; modify `AlgorithmPatternAnalyzerTest.java` and `PatternBindingRegressionTest.java`.

**Interfaces:** `TwoPointersDetector.detect(CompilationUnit unit, Node loop): Optional<AlgorithmHint>` consumes the Task 1 candidate/proof and constructs its `TwoPointersPattern`. Its declaration IDs use `data`, `left`, `right` for opposing movement and `data`, `scan`, `slow` for same-direction movement. Existing hint type remains `two-pointers`; static metadata appears in nullable `AlgorithmHint.twoPointers`.

- [ ] Write positive tests for array reversal, two-sum style conditional movement, pair comparison, `List.get`/`set`, changed names and reversed guards. Assert movement `OPPOSING`, same input declaration for both accesses, exact operation IDs and one hint per loop.
- [ ] Write negative tests for `do/while` without an empty-input guard, either pointer moving outward, a branch that continues without moving, accesses after crossing, distinct inputs and unrelated reads. Assert no `two-pointers` hint. Keep existing binary-search/sort tests green.
- [ ] Run `mvn -q -Dtest=TwoPointersDetectorTest,AlgorithmPatternAnalyzerTest,PatternBindingRegressionTest test`; require the new cases to fail.
- [ ] Implement opposing-end proof in `TwoPointersDetector`, wire it after sliding window in `AlgorithmPatternAnalyzer`, and remove the old `twoPointers` heuristic. Include `DoStmt` in loop traversal for the new detector without expanding old detectors' acceptance. Add the nullable pattern field and compatibility constructors to `AlgorithmHint`.
- [ ] Rerun `mvn -q -Dtest=TwoPointersDetectorTest,AlgorithmPatternAnalyzerTest,PatternBindingRegressionTest test`; require pass and unchanged recognition order.

### Task 3: Recognize Same-Direction Pointers

**Files:** Modify `PointerProgressAnalyzer.java`, `TwoPointersDetector.java`; extend `PointerProgressAnalyzerTest.java`, `TwoPointersDetectorTest.java`, `AlgorithmPatternAnalyzerTest.java`.

**Interfaces:** Reuse `TwoPointersDetector.detect`; metadata movement is `SAME_DIRECTION`, roles are `scan` and `slow` rather than `left` and `right`.

- [ ] Write positives for conditional compact/filter, adjacent-element comparison and `List<Integer>` variants with `scan` advancing and `slow` staying or advancing; include `for`, `while`, immutable alias and different update syntax.
- [ ] Write negatives for two indices always equal and incremented together, `slow` passing `scan`, `scan` stalling on a continuing path, backward `slow`, unrelated copy and accumulator-based sliding window. Assert sliding window keeps its earlier hint where applicable.
- [ ] Run `mvn -q -Dtest=TwoPointersDetectorTest,AlgorithmPatternAnalyzerTest,PointerProgressAnalyzerTest test`; require the new cases to fail.
- [ ] Implement same-direction proof and source metadata without weakening opposing-pointer checks; rerun `mvn -q -Dtest=TwoPointersDetectorTest,AlgorithmPatternAnalyzerTest,PointerProgressAnalyzerTest test` and require pass.

### Task 4: Project Integer Lists Safely

**Files:** Create `backend/src/main/java/com/example/astchunker/mapping/IntegerListProjection.java`; modify `VariableMapper.java`, `JdiSession.java`, `DebugController.java`; extend `VariableMapperTest.java`, `VariableMapperFormattingTest.java`, `JdiSessionIntegrationTest.java`.

**Interfaces:** `IntegerListProjection.fromHints(List<AlgorithmHint>): IntegerListProjection` selects data declaration IDs. Add `JdiSession.observe(CompiledTarget, Analysis, IntervalValueProjection, IntegerListProjection)` and `VariableMapper.map(StackFrame, ObservationPoint, List<AstVariable>, int, IntervalValueProjection, IntegerListProjection)`; old overloads delegate to empty projections. For approved inputs, `ObservationResult.visualType = "integer-list"` and `visualValue` has `values`, `length`, `truncated`, `status`; preserve `objectId`.

- [ ] Write tests for exact `ArrayList<Integer>`/`LinkedList<Integer>`, empty/singleton/negative/duplicate values, `null`, >100 items, inaccessible fields and a subclass overriding `get`. Assert bounded data and `UNKNOWN`/`TRUNCATED` rather than fabricated integers; `/api/debug` keeps its old shape without a hint.
- [ ] Run `mvn -q -Dtest=VariableMapperTest,VariableMapperFormattingTest,JdiSessionIntegrationTest test`; require the new cases to fail.
- [ ] Read only `size`, backing array or linked nodes, and `java.lang.Integer.value` through JDI; do not invoke methods. Keep each element's position, including duplicate values, and mark unknown elements explicitly. Pass projection from `/api/debug/steps` only.
- [ ] Rerun `mvn -q -Dtest=VariableMapperTest,VariableMapperFormattingTest,JdiSessionIntegrationTest test`; require pass.

### Task 5: Build Two-Pointer Runtime Frames

**Files:** Create `backend/src/main/java/com/example/astchunker/model/TwoPointersFrame.java`, `backend/src/main/java/com/example/astchunker/visualization/TwoPointersTraceBuilder.java`; modify `VisualState.java`, `VisualTraceBuilder.java`; create `backend/src/test/java/com/example/astchunker/visualization/TwoPointersTraceBuilderTest.java`; extend `VisualTraceIntegrationTest.java`.

**Interfaces:** `TwoPointersTraceBuilder(List<AlgorithmHint> hints, Map<String,Node> nodes)` and `states(ExecutionObservation step, Node point, String mainClass): List<VisualState>`. Define `TwoPointersFrame(Long invocationId, Long inputObjectId, String movement, Long firstIndex, Long secondIndex, Integer length, Object firstValue, Object secondValue, Long firstDelta, Long secondDelta, String effectStatus, List<String> sourceEvidence, String snapshotPhase)`. Status is `PENDING`, `OBSERVED`, `UNKNOWN`, `TRUNCATED` or `VIOLATED`; first/second map to left/right or scan/slow by movement. Add nullable `VisualState.twoPointersFrame`; old constructors delegate with `null`.

- [ ] Write snapshot tests for both movement kinds and data types: a pointer update or data write location first yields `PENDING`, a later observed state for the same operation yields `OBSERVED`, a contradictory move yields `VIOLATED`. Assert empty/singleton and out-of-bounds produce safe frames.
- [ ] Write isolation tests for same-content distinct objects, same object in separate invocations, multiple threads, missing observations, truncated/null list values and multiple writes on one line. Assert no cross-context confirmation and no invented intermediate move.
- [ ] Run `mvn -q -Dtest=TwoPointersTraceBuilderTest,VisualTraceIntegrationTest test`; require the new cases to fail.
- [ ] Implement per-context trace state, bind via declaration IDs and operation node IDs, use the existing pre-location snapshot semantics, and integrate after old generic states without duplicate two-pointer states. Unknown input or skipped breakpoint remains `UNKNOWN`/`TRUNCATED`.
- [ ] Rerun `mvn -q -Dtest=TwoPointersTraceBuilderTest,VisualTraceIntegrationTest test`; require pass.

### Task 6: Expose, Render and Document

**Files:** Modify `UI/dashboardPage.html`, `README.md`, `backend/src/test/java/com/example/astchunker/controller/MultipartApiIntegrationTest.java`; create `backend/examples/twopointers/OpposingArraySample.java`, `SameDirectionListSample.java`, `docs/two-pointers-test-guide.md`.

**Interfaces:** `/api/debug/steps` adds nullable `algorithmHints[].twoPointers` and `steps[].visualStates[].twoPointersFrame`; `/api/debug` stays unchanged. UI reads the frame defensively and retains generic variables when it is absent.

- [ ] Add API tests for both movement kinds and both representations, stable old JSON fields, absence of false correctness labels, and null/missing frames. Run `mvn -q -Dtest=MultipartApiIntegrationTest test`; require the new assertions to fail.
- [ ] Render pointer indices, current values, between-pointer or scanned range, source evidence and runtime effect status with escaped text. Add playback for `two-pointers` and retain the generic variable table.
- [ ] Add compile-ready examples and a short guide with supported syntax, unsupported cases, evidence labels and upload steps. Rerun `mvn -q -Dtest=MultipartApiIntegrationTest test`; require pass.
- [ ] Run `mvn -q -Dtest=TwoPointersDetectorTest,VariableMapperTest,TwoPointersTraceBuilderTest,MultipartApiIntegrationTest test`, then `mvn -q test`. Check `git diff --check`, UI inline script with `node --check`, and `mvn -q spotless:check` if the available JDK supports its Google Java Format version. Record any formatter/JDK limitation accurately.
- [ ] Review final diff for false hints, old endpoint behavior, data disclosure and unrelated user changes. Leave implementation uncommitted and unpushed unless requested.

## Verification

This machine has Maven at `/home/hien/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn` and a Java 25 runtime at `/home/hien/.vscode/extensions/redhat.java-1.57.0-linux-x64/jre/25.0.4.1-linux-x86_64`. For every `mvn` command above, use this absolute Maven path with `JAVA_HOME` set to that JDK and `JAVA_TOOL_OPTIONS=-Dnet.bytebuddy.experimental=true`. For example, from `backend/`:

```bash
JAVA_HOME='/home/hien/.vscode/extensions/redhat.java-1.57.0-linux-x64/jre/25.0.4.1-linux-x86_64' JAVA_TOOL_OPTIONS='-Dnet.bytebuddy.experimental=true' '/home/hien/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn' -q -Dtest=TwoPointersDetectorTest test
```

Repeat with each task's listed test names, then without `-Dtest` for the full suite. The previous Activity Selection suite passed with these flags; `spotless:check` was blocked by this JDK's compatibility with the pinned formatter, so do not treat that check as passed unless it actually runs successfully. No new dependency is required.
