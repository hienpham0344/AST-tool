# Bubble Sort `while` Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize canonical Bubble Sort written with nested `while` loops, while preserving existing `for` recognition and runtime visualization.

**Roadmap role:** This is the first testable step of [semantic sort recognition](2026-10-17-sort-quality-roadmap.md). Passing this plan alone does not complete the broader requirement to recognize equivalent structural rewrites of all three existing sorts.

**Architecture:** Keep algorithm identification based on JavaParser AST and lexical variable bindings. Normalize only the counted-loop forms needed by Bubble Sort into a small internal representation, then reuse the current adjacent-comparison and three-assignment swap checks. Keep JDI responsible for values shown after recognition, not for assigning the algorithm label.

**Tech Stack:** Java 17, JavaParser, JDI, JUnit 5, Maven, existing vanilla JavaScript UI.

**Spec:** The Bubble Sort `while` example and requested behavior in this conversation on 2026-10-17; current behavior and limits are documented in `docs/sort-visualization-test-guide.md`.

## Global Constraints

- Scope this plan to Bubble Sort on `int[]`, ascending and descending. Selection Sort and Insertion Sort `while` variants need separate follow-up plans after this loop representation is proven.
- Accept only a clearly matched initialization, bound, unconditional increment, adjacent comparison, and ordered three-statement swap. An ambiguous or modified loop returns no hint.
- Variable names may differ, but every role must resolve to one lexical declaration using `LocalVariableBindings`.
- Preserve `AlgorithmHint`, `SortPattern`, `/api/debug/steps`, `/api/debug`, and the UI response shapes. Do not add dependencies.
- Runtime snapshots enrich an AST hint; they must not turn an unmatched source pattern into a Bubble Sort hint.

## Review Focus

- A counter declared outside a `while` loop must be the same declaration used in its condition and increment; test shadowed names.
- A conditional or missing increment must not be treated as a counted loop; test both.
- A side effect added to either loop body must not silently match; test an extra array write.
- The comparison and swap must target the same adjacent `int[]` elements; test a two-slot offset and a second array.
- Empty, one-element, duplicate, and descending inputs must retain correct final trace status; cover these in runtime tests.

---

### Task 1: Describe the accepted `while` shape with failing detector tests

**Files:** Modify `backend/src/test/java/com/example/astchunker/algorithm/BubbleSortDetectorTest.java`.

**Interfaces:** `AlgorithmPatternAnalyzer.analyze(CompilationUnit)` returns `List<AlgorithmHint>`; no production interface changes yet.

- [ ] Add `detectsNestedWhileBubbleWithRenamedVariables`: start from `int pass = 0; while (pass < data.length - 1) { int scan = 0; while (scan < data.length - 1 - pass) { if (data[scan] > data[scan + 1]) { int saved = data[scan]; data[scan] = data[scan + 1]; data[scan + 1] = saved; } scan++; } pass++; }`. Assert one `bubble-sort` hint, ASCENDING, role names, declaration IDs, and nonempty AST IDs for comparison, inner loop, condition and three swap statements.
- [ ] Add the descending counterpart using `<` and assert DESCENDING. Assert that the existing `for` cases still emit the same metadata.
- [ ] Add parameterized near-miss tests for missing or conditional `scan++`, extra loop-body array write, `scan + 2`, comparison against another array, `long[]`, and shadowed `scan`; assert no `bubble-sort` hint.
- [ ] Run `mvn -Dtest=BubbleSortDetectorTest test` from `backend` and record that the new positive tests fail for the expected missing recognition.

### Task 2: Normalize the two counted-loop forms and recognize `while` Bubble Sort

**Files:** Create `backend/src/main/java/com/example/astchunker/algorithm/sort/CountedLoop.java`; modify `backend/src/main/java/com/example/astchunker/algorithm/sort/BubbleSortDetector.java` and, only for shared expression helpers, `backend/src/main/java/com/example/astchunker/algorithm/sort/SortAstSupport.java`.

**Interfaces:** Package-private `CountedLoop.match(Node loop, String initialValue): Optional<CountedLoop>` exposes `node()`, `counter()`, `condition()`, and `bodyStatements()`; `bodyStatements()` excludes only the verified unconditional final counter increment. `BubbleSortDetector.detect(Node)` continues returning `Optional<AlgorithmHint>`.

- [ ] Implement the `for` adapter using the existing `SortAstSupport.counter` rules. Keep its current accepted counter syntax and body semantics.
- [ ] Implement the `while` adapter: require an immediately preceding `int` counter declaration initialized to the requested value, a final unconditional increment in the loop body, and no ambiguity in declaration binding. Accept `x++`, `++x`, `x += 1`, and `x = x + 1`; reject other writes to that counter inside the body.
- [ ] Refactor `BubbleSortDetector` to match the outer loop through `CountedLoop`, then its inner loop. For `while`, require the inner counter declaration immediately before the inner loop in the outer body. Keep the current exact bounds, adjacent strict comparison, three swap statements, `int[]` type check, and lexical role binding.
- [ ] Emit `SortPattern` IDs from the real AST nodes of the detected loops and statements. Preserve the existing direction mapping and confidence value `0.95`.
- [ ] Run `mvn -Dtest=BubbleSortDetectorTest test` from `backend`; all positive and negative cases must pass.
- [ ] Commit the focused detector change and tests.

### Task 3: Verify the visualization against real `while` executions

**Files:** Modify `backend/src/test/java/com/example/astchunker/visualization/SortRuntimeIntegrationTest.java` and `docs/sort-visualization-test-guide.md`; modify production trace code only if a failing integration test identifies a concrete defect.

**Interfaces:** Keep `VisualTraceBuilder.build(Analysis, List<AlgorithmHint>, List<ExecutionObservation>)` and the `/api/debug/steps` payload unchanged.

- [ ] Add a `while` Bubble Sort runtime case using the same source shape as Task 1. Reuse the six input arrays in the current parameterized integration test and assert the final values and `VERIFIED_SORTED` status for each.
- [ ] Add a descending `while` run and assert the final array order and status. Assert at least one comparison and one completed swap frame on an unsorted input, so the UI has meaningful operation data.
- [ ] Run `mvn -Dtest=SortRuntimeIntegrationTest,VisualTraceIntegrationTest test` from `backend`; address only failures caused by `while` support.
- [ ] Update the guide with the newly accepted `while` shape and explicit limits: no arbitrary helper `swap`, `do-while`, or proof of correctness for every syntactic variant.
- [ ] Run `mvn test` from `backend`, then `git diff --check` and inspect the diff. Commit the integration test and documentation change.

## Completion criteria

- The two `for` examples and the nested `while` example from the conversation produce a Bubble Sort hint despite different variable names.
- The listed near misses produce no Bubble Sort hint.
- Runtime steps for `while` carry the same sort visualization metadata as `for`, with correct final array values for the tested inputs.
- All backend tests pass; no API or UI schema changes are needed.
