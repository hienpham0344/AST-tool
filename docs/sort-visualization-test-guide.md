# Sort Visualization Test Guide

## Supported patterns

The current conservative AST detectors visualize Bubble Sort, Selection Sort,
and Insertion Sort over `int[]`, in ascending or descending order when the
canonical comparison is recognized. A valid implementation written in a
different structure may not be recognized; no hint is not proof that the user's
algorithm is wrong. Merge, Quick, Heap, Shell, Counting, and Radix Sort are not
covered yet.

## Source rewrite corpus for the next recognition phase

The current baseline is represented by `backend/src/test/resources/sort-patterns/*-for.java`.
The same directory also contains planned equivalent rewrites that are **not yet recognized**:
`bubble-while.java`, `bubble-rewritten-bound.java`, `bubble-helper-swap.java`,
`selection-while.java`, and `insertion-while.java`. A detector must match the array reads,
comparison, counter progress, and actual writes; matching a variable name or one sorted output
is insufficient. The three `*-wrong-*` / `*-incomplete-*` samples are close negative cases.

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
   `examples/sort/InsertionSortSample.java`.
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
