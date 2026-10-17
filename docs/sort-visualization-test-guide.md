# Sort Visualization Test Guide

## Supported patterns

The conservative AST detectors recognize Bubble Sort, Selection Sort, and
Insertion Sort over `int[]` in ascending or descending order. They accept the
tested `for`/`while` rewrites, counter update spellings, equivalent loop bounds,
renamed locals, and equivalent comparison order. Bubble also supports an
early-exit flag; Bubble and Selection accept a direct three-statement swap or
a simple same-class `swap(int[], int, int)` helper. Insertion accepts either
declaration order for its key and scan index.

A valid implementation outside these shapes may not be recognized; no hint
does not prove that the user's algorithm is wrong. Unclear array aliases,
unknown helper calls, extra writes, and non-`int[]` arrays fall back to general
runtime observation. Merge, Quick, Heap, Shell, Counting, and Radix Sort are
not covered yet.

## Source rewrite corpus

The corpus in `backend/src/test/resources/sort-patterns/` contains supported
canonical, `while`, rewritten-bound, early-exit, helper-swap, and reordered-local
examples. Close negative cases include incomplete swaps, wrong indices, wrong
shifts, and an early-exit flag that is never set. Each detector requires the
matching array reads, comparison, counter progress, and writes; variable names
or one sorted output alone are insufficient.

The order of implementation and review gates is in
[`docs/superpowers/plans/2026-10-17-sort-increments.md`](superpowers/plans/2026-10-17-sort-increments.md).

The Java samples under `examples/sort/` each call one `sort(int[] nums)` method
for six inputs: empty, one value, sorted, reversed, duplicates, and negative
values. This demonstrates that the detector uses the array observed at runtime,
not a single sample literal.

## Run it in the UI

1. From the repository root, start the backend in one terminal:

   ```powershell
   cd backend
   mvn spring-boot:run
   ```

2. Open `UI/dashboardPage.html` with Live Server, usually at
   `http://127.0.0.1:5500/UI/dashboardPage.html`.
3. Upload one of `examples/sort/BubbleSortSample.java`,
   `examples/sort/SelectionSortSample.java`, or
   `examples/sort/InsertionSortSample.java`. The matching
   `BubbleWhileSample.java`, `SelectionWhileSample.java`, and
   `InsertionWhileSample.java` exercise the supported `while` rewrites.
4. Click **Quan sát biến runtime**. Use Previous/Next, Play, the step slider,
   and speed selector. The step, source highlight, variable table, and indexed
   array view should move together.

Each sample calls `sort()` repeatedly in one backend run. The displayed array,
indices, key, and comparison values should change as execution moves between
cases. Each `sort()` includes a statement after its loop so the backend can
capture and verify the final state before returning to `main`.

## Read the network JSON

In DevTools → Network → Fetch/XHR → `steps` → Preview, inspect:

- `algorithmHints[n].type`, `.variables`, and `.variableDeclarationIds` for the
  detected pattern and source-to-runtime bindings.
- `algorithmHints[n].sort.direction` for ascending/descending order and
  `.sort.operationAstNodeIds` for operation source locations.
- `steps[i].lineNumber`, `.code`, and `.snapshotPhase` for the current source
  location. A visual sort snapshot is `BEFORE_LOCATION`.
- `steps[i].visualStates[m].array.values` for the actual captured array,
  `.pointers.pass` / `.pointers.scan`, and `.scalars.key` for insertion variables.
- `steps[i].visualStates[m].sortFrame.phase`, `.comparison`,
  `.pendingMutation`, and `.completedMutation` for the current decision and
  writes. A pending write is not presented as complete until a later snapshot
  confirms it.
- `.sortFrame.sortedRegion.status`: `VERIFIED_SORTED` means the captured range
  passed an actual runtime order check; `NOT_SORTED` gives the first adjacent
  inversion; `TRUNCATED` and `UNKNOWN` are deliberately not treated as success.

The sort label means the source matches a supported structure. A region is
runtime verified only when its `sortedRegion.status` is `VERIFIED_SORTED` in a
snapshot that contains the full region. A pending write is still a prediction
at the current source location; `completedMutation.status=OBSERVED_AFTER_WRITE`
requires a later confirming snapshot.

For insertion, `comparison.action` is `SHIFT_RIGHT` or `STOP_SHIFTING`;
`LEFT_BOUNDARY_REACHED` means the saved key should be written at index zero.

## Automated verification

From `backend`, run the focused JDI suite:

```powershell
mvn "-DforkCount=0" "-Dtest=BubbleSortDetectorTest,SelectionSortDetectorTest,InsertionSortDetectorTest,VisualTraceIntegrationTest,SortRuntimeIntegrationTest" test
```

Then run the full suite:

```powershell
mvn "-DforkCount=0" test
```

The parameterized runtime tests compare the final output for six arrays across
all three ascending algorithms; dedicated tests also cover descending runtime
and the per-step pending/completed operations.

`-DforkCount=0` runs tests in the Maven process. It avoids Mockito self-attach
failures seen with Surefire's separate test JVM in this Windows environment.
