# Heap Sort Semantic Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` (native execution selected earlier with `-auto`) or `superpowers:subagent-driven-development` if the user changes that choice. Track the checkboxes task by task. The user's Git instructions override skill commit steps: do not commit or push unless asked.

**Goal:** Recognize and visualize supported Heap Sort implementations from proven heap effects across different source shapes, without labeling nearby incorrect programs.

**Architecture:** Normalize bounded index/boundary expressions and summarize sift operations before proving a complete build/extract flow. Emit additive AST metadata; join it with JDI observations by node, invocation, and array identity. Keep recognition and runtime verification separate.

**Tech Stack:** Java 17, JavaParser, JDI, JUnit 5, Maven, existing HTML/JavaScript UI.

**Spec:** [Heap Sort Semantic Recognition Design](../specs/2026-10-18-heap-sort-semantic-recognition-design.md)

## Global Constraints

- Keep `/api/debug` and existing `/api/debug/steps` fields compatible; add nullable `heap` and `heapFrame` only.
- Work in the existing `codex/semantic-sort-foundation` worktree; preserve its uncommitted Merge/Quick changes and the primary `main` checkout.
- Prove effects on the same `int[]` declaration. Do not infer a heap from method/variable names or sorted sample output.
- Ambiguous alias, helper dispatch, control flow, or array write means no Heap hint; generic runtime observations remain available.
- A predicted operation is pending until a later JDI snapshot confirms it. Missing or truncated values never become verified.
- No new dependency or lockfile change. Do not commit or push without a new user request.

## Review Focus

1. A local/field named like the class changes a qualified helper call's target: Task 2 rejects the candidate.
2. A source boundary expressed as an inclusive last index is off by one after normalization: Tasks 1 and 3 test both forms at lengths 0, 1, 2, and odd/even lengths.
3. A call sorts a different array or a local alias is reassigned: Tasks 2 and 3 reject the hint; Task 5 does not link another array's root.
4. An `if` branch skips the right child or writes into the extracted suffix: Tasks 2 and 3 reject the hint.
5. A JDI snapshot misses a helper write or contains only an array prefix: Task 5 keeps mutation/status pending or unknown.

## File map

- `algorithm/sort/HeapIndexExpressions.java`: pure normalization of child/parent arithmetic and heap boundary conventions, with a bounded single-assignment alias check.
- `algorithm/sort/HeapSiftAnalyzer.java`: proof summary for inline or same-class `siftDown`/`siftUp`, including comparisons, progress, swaps, and helper dispatch.
- `algorithm/sort/HeapSortDetector.java`: combine build and extract proofs; emit operation AST IDs and role declarations.
- `model/HeapPattern.java`, `model/HeapFrame.java`: additive source metadata and runtime view.
- `visualization/HeapTraceBuilder.java`: JDI-backed heap/array verification and operation transitions.
- Existing `SortPatternRegistry`, `AlgorithmHint`, `VisualState`, `VisualTraceBuilder`, `UI/dashboardPage.html`, guide, examples, and targeted tests: integration only.

## Task 1 — Normalize semantic index and boundary facts

**Files:** Create `backend/src/main/java/com/example/astchunker/algorithm/sort/HeapIndexExpressions.java`; test `backend/src/test/java/com/example/astchunker/algorithm/sort/HeapIndexExpressionsTest.java`.

**Interface:** `static Optional<ChildSide> child(Expression expression, String parent, String provenLeft)` with `ChildSide.LEFT/RIGHT`; `static boolean parent(Expression expression, String child)`; `static Optional<HeapBoundary> boundary(Expression expression, String sizeOrLast, boolean inclusive)` where `HeapBoundary` records the normalized exclusive heap end. The implementation must reject writes/side effects and reassigned aliases rather than flattening arbitrary expressions.

- [ ] Add positive tests for `2*i+1`, `i*2+1`, `(i<<1)+1`, `2*i+2`, proven `left+1`, `(i-1)/2`, and exclusive/inclusive heap ends. Add negatives for `2*i`, `2*i+3`, unrelated `left`, side-effecting expressions, and mutable aliases.
- [ ] Run `mvn -Dtest=HeapIndexExpressionsTest test`; record the expected red failures.
- [ ] Implement the minimum normalization and declaration-based alias proof, reusing `SortAstSupport`/`LocalVariableBindings` where valid.
- [ ] Run the focused test green and `git diff --check`.

## Task 2 — Prove sift and swap effects

**Files:** Create `backend/src/main/java/com/example/astchunker/algorithm/sort/HeapSiftAnalyzer.java`; test `backend/src/test/java/com/example/astchunker/algorithm/sort/HeapSiftAnalyzerTest.java`; reuse `ArrayEffects` and `SortNameResolution` without broad refactoring.

**Interface:** `static Optional<HeapSiftProof> prove(Node bodyOrCall, MethodDeclaration sortMethod, String arrayDeclarationId, Direction direction, String boundaryDeclarationId)`. `HeapSiftProof` records `UP`/`DOWN`, child choice, progress, swap operation IDs, optional helper method/call IDs, and relevant role declaration IDs. Only complete, non-overridable same-class helpers qualify.

- [ ] Add positive fixtures for `siftDown` iterative/recursive and `siftUp`, inline and proven helper, both sort directions, swapped comparison operands, and renamed/reordered helper parameters.
- [ ] Add near-negatives for wrong child selection, missing right-child check, incomplete swap, no progress, suffix write, foreign or overridable helper, class-name shadowing, and a different array argument.
- [ ] Run the focused tests red, implement the proof summary, then run them green. The proof cannot succeed merely because a helper is named `heapify`.

## Task 3 — Prove bottom-up build and extraction

**Files:** Create `backend/src/main/java/com/example/astchunker/algorithm/sort/HeapSortDetector.java` and `backend/src/main/java/com/example/astchunker/model/HeapPattern.java`; modify `backend/src/main/java/com/example/astchunker/algorithm/sort/SortPatternRegistry.java` and `backend/src/main/java/com/example/astchunker/model/AlgorithmHint.java`; test `backend/src/test/java/com/example/astchunker/algorithm/HeapSortDetectorTest.java` and `backend/src/test/java/com/example/astchunker/algorithm/SortPatternCorpusTest.java`.

**Interface:** `HeapSortDetector implements SortPatternDetector`; `detect(Node loop)` returns one `AlgorithmHint(type="heap-sort")` only after build/extract, same-array, heap-boundary, and suffix-write proofs. `HeapPattern` contains version, direction, build kind, declaration-role IDs, and operation AST IDs. Add a nullable `heap` record component with legacy `AlgorithmHint` constructors preserved.

- [ ] Add bottom-up max/min heap fixtures using `for` and `while`, `heapSize` and inclusive `lastIndex`, renamed roles, direct/helper swap, and a combined rewrite. Add six input arrays plus odd/even lengths.
- [ ] Add negatives for missing build, wrong build start, root not swapped to old heap end, heap size unchanged, sift using old end, writing the extracted suffix, wrong array, and extra writes.
- [ ] Run detector/JSON compatibility tests red. Implement proof aggregation and additive metadata; run focused tests green.

## Task 4 — Accept top-down heap construction

**Files:** Extend `HeapSortDetector.java` and `HeapSortDetectorTest.java`; add positive/negative source fixtures under `backend/src/test/resources/sort-patterns/` if the corpus runner supports them.

**Interface:** Keep `HeapSortDetector.detect(Node)` and `HeapPattern` unchanged. Accept build-by-insertion only when a proved `siftUp` extends the valid heap by one at every step; extraction still uses the Task 3 proof.

- [ ] Add positive tests for increasing build boundary with `siftUp`, both directions, `for`/`while`, inline/helper, and a combination with reversed comparisons.
- [ ] Add negatives for skipping an input element, shrinking the build boundary, wrong parent, an alias reassignment, and no heap restoration before extraction.
- [ ] Run red, add the second build proof, run focused detector and prior sort detector tests green.

## Task 5 — Runtime trace and safety of verification

**Files:** Create `backend/src/main/java/com/example/astchunker/model/HeapFrame.java` and `backend/src/main/java/com/example/astchunker/visualization/HeapTraceBuilder.java`; modify `backend/src/main/java/com/example/astchunker/model/VisualState.java` and `backend/src/main/java/com/example/astchunker/visualization/VisualTraceBuilder.java`; test `backend/src/test/java/com/example/astchunker/visualization/HeapSortRuntimeIntegrationTest.java` and `HeapTraceBuilderRangeTest.java`.

**Interface:** `HeapTraceBuilder(List<AlgorithmHint>, Map<String,Node>)` and `states(ExecutionObservation, Node, String) -> List<VisualState>`, matching Quick/Merge builders. Add nullable `heapFrame` to `VisualState`, preserving older constructors. `HeapFrame` contains phase, invocation, exclusive heap end, node/children/chosen child, comparison, pending/completed mutation, heap/suffix/full-array status, and `BEFORE_LOCATION`.

- [ ] Add JDI tests for empty, singleton, sorted, reversed, duplicate, negative, odd/even, descending, repeated roots, nested helper calls, and a combined source rewrite. Check every displayed comparison against observed values.
- [ ] Add tests that a pending swap becomes confirmed only after the two observed writes on the same array/invocation; helper observations must not confirm another caller's pending mutation.
- [ ] Add missing/truncated snapshots and wrong-order cases; assert `UNKNOWN`/`TRUNCATED`/`NOT_SORTED` correctly and no false `VERIFIED_SORTED`.
- [ ] Run focused tests red, implement trace and heap/suffix checks, then run focused tests green.

## Task 6 — UI, API, documentation, final review

**Files:** Modify `UI/dashboardPage.html`, `docs/sort-visualization-test-guide.md`, `README.md`; create `examples/sort/HeapSortSample.java`; extend `backend/src/test/java/com/example/astchunker/controller/MultipartApiIntegrationTest.java` if needed for additive JSON.

**Interface:** `algorithmHints[n].heap` and `steps[i].visualStates[m].heapFrame` are additive. The UI selects `heapFrame` for a heap hint and renders observed heap boundary, parent/children, pending/completed swap, verified suffix, and full-array status.

- [ ] Add API assertions for new metadata with legacy fields still parseable; test generic observation when no Heap hint exists.
- [ ] Implement the UI and sample; check source-highlight alignment and the wording of recognized versus runtime-verified states. Run `node --check` on the extracted script; browser smoke only if an allowed browser surface is available.
- [ ] Update guide with accepted rewrites and explicit unsupported families. Run focused tests, the full backend suite, targeted formatting, `git diff --check`, and one read-only code review. Record actual evidence and any unverified UI behavior.
