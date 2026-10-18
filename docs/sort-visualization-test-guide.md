# Sort Visualization Test Guide

## Supported patterns

The conservative AST detectors recognize Bubble Sort, Selection Sort, and
Insertion Sort over `int[]` in ascending or descending order. They accept the
tested `for`/`while` rewrites, counter update spellings, equivalent loop bounds,
renamed locals, and equivalent comparison order. Bubble also supports an
early-exit flag; Bubble and Selection accept a direct three-statement swap or
a simple same-class `swap(int[], int, int)` helper when Java dispatch cannot
override it (a static, private, or final method, or a final class). Bubble also
accepts the tested combination of `while` loops and early exit. Insertion accepts either
declaration order for its key and scan index.

A valid implementation outside these shapes may not be recognized; no hint
does not prove that the user's algorithm is wrong. Unclear array aliases,
unknown helper calls, extra writes, and non-`int[]` arrays fall back to general
runtime observation. Quick Sort additionally recognizes the documented
[inline Lomuto family](superpowers/plans/2026-10-18-quick-sort-gate.md): an
inclusive `low`/`high` range, last-element pivot, `for` or equivalent `while`
scan, proven inline swaps, a one-step store advance (`++`, `+= 1`, or
`store = store + 1`), and both recursive subranges. It accepts an early-return
guard or an equivalent `if (low < high) { ... }` body, reversed comparison
operands, same-class static qualification, and `this` recursion in an instance
method. A private instance call through a local object can be linked to its
caller snapshot. Other Quick partition
families and helper calls currently fall back. Merge Sort recognizes the
documented [top-down auxiliary-array family](superpowers/plans/2026-10-18-merge-sort-gate.md):
inclusive recursive halves, a complete merge into `int[] aux`, two remainder
loops, and full copy-back. It accepts renamed variables, both directions,
reversed comparison operands, the equivalent positive recursive guard,
same-class static qualification or `this` recursion, and `for`/`while`
copy-back. Class-qualified recursion is accepted only when the class name is
not shadowed by a variable or field. Other Merge structures and opaque helpers
fall back. Heap Sort recognizes a complete in-place `int[]` build/extract flow:
bottom-up `siftDown` or top-down `siftUp` construction, root/end swap, shrinking
heap boundary, and `siftDown` restoration. Tested variants include renamed
roles, reordered parameters in a non-overridable same-class helper, `for`/`while`
build and extract loops, iterative or recursive `siftDown`, min/max heaps,
reversed comparison operands, and exclusive size or inclusive last-index
boundaries, and the tested inline `siftDown` body in both build and extract
loops. An immutable local array alias is accepted. A wrong child choice, missing restoration, extra suffix write,
unproven helper call, or a different array falls back to general runtime
observation. Arbitrary heap indexing rewrites are not recognized yet. Shell,
Counting, and Radix Sort are not covered yet.

## Source rewrite corpus

The corpus in `backend/src/test/resources/sort-patterns/` contains supported
canonical, `while`, rewritten-bound, early-exit, helper-swap, and reordered-local
examples in both sort directions. Every supported example has a paired wrong-write
negative and a runtime trace check for observed comparison, pending and confirmed
mutation, and final sorted array. Other close negatives include incomplete swaps,
wrong indices, wrong shifts, an overridable helper, and an early-exit flag that is
never set. Each detector requires the
matching array reads, comparison, counter progress, and writes; variable names
or one sorted output alone are insufficient.

The order of implementation and review gates is in
[`docs/superpowers/plans/2026-10-17-sort-increments.md`](superpowers/plans/2026-10-17-sort-increments.md).

The Java samples under `examples/sort/` exercise one sorting method
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
   `QuickSortSample.java` exercises the supported recursive Lomuto family;
   `MergeSortSample.java` exercises the supported top-down merge family;
   `HeapSortSample.java` exercises the supported bottom-up heap family.
4. Click **Quan sát biến runtime**. Use Previous/Next, Play, the step slider,
   and speed selector. The step, source highlight, variable table, and indexed
   array view should move together.

Each sample sorts multiple arrays in one backend run. The displayed array,
indices, key, and comparison values should change as execution moves between
cases. The iterative samples include an observation point after the sort loop;
Quick, Merge, and Heap verify the complete array at the next observed caller
point for the documented call forms. Quick and Merge also support a private
instance call through a local object. If the caller snapshot is missing or
truncated, the full-array status remains unknown.

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
- For Quick Sort, `steps[i].context.invocationId` identifies one recursive
  call. `visualStates[m].quickFrame` shows the active range, pivot, comparison,
  pending and confirmed swap, and verified partition. Only the pivot position
  is highlighted as fixed after partition. A full-array result is checked at
  the next observed caller point after the root invocation, including a
  pass-through wrapper that ends with the Quick call, and appears in
  `quickFrame.fullArrayStatus`. A captured prefix of a larger array is shown
  as `TRUNCATED` and cannot receive full-array verification.
- For Merge Sort, `algorithmHints[n].merge.operationAstNodeIds` identifies the
  recursive calls, comparisons, auxiliary writes, and copy-back. The
  `mergeFrame` contains the source range and a separate auxiliary snapshot.
  `pendingMutation` describes a write that has not run at the current source
  location; `completedMutation` requires a later snapshot. A child range is
  verified on return to its parent, and `fullArrayStatus` is verified at a
  caller point after the root call. Missing or truncated snapshots remain
  `UNKNOWN`; only an observed ordering violation is `NOT_SORTED`.
- For Heap Sort, `algorithmHints[n].heap` contains the direction, build kind,
  source operation IDs, and the proved inclusive-boundary flags.
  `heapFrame.heapEnd` is exclusive; `verifiedSuffixStart` remains null until a
  root swap has been observed completely. The frame shows `siftUp` and
  `siftDown` comparisons, pending and completed swaps, heap and suffix
  checks, and full-array order after the sort call returns. Missing or
  truncated arrays remain `UNKNOWN` or `TRUNCATED`; a pending swap is not
  presented as completed until later snapshots show both writes on the same
  array and invocation.

The sort label means the source matches a supported structure. For iterative
sorts, a region is runtime verified only when `sortedRegion.status` is
`VERIFIED_SORTED` in a snapshot that contains the full region. Quick and Merge
use their own frame status fields described above. Heap uses `heapFrame`.
A pending write is still a
prediction at the current source location;
`completedMutation.status=OBSERVED_AFTER_WRITE` requires a later confirming
snapshot.

For insertion, `comparison.action` is `SHIFT_RIGHT` or `STOP_SHIFTING`;
`LEFT_BOUNDARY_REACHED` means the saved key should be written at index zero.

## Automated verification

From `backend`, run the focused JDI suite:

```powershell
mvn "-DforkCount=0" "-Dtest=BubbleSortDetectorTest,SelectionSortDetectorTest,InsertionSortDetectorTest,QuickSortDetectorTest,QuickSortRuntimeIntegrationTest,MergeSortDetectorTest,MergeSortRuntimeIntegrationTest,MergeTraceBuilderRangeTest,HeapIndexExpressionsTest,HeapSiftAnalyzerTest,HeapSortDetectorTest,HeapSortRuntimeIntegrationTest,HeapTraceBuilderRangeTest,VisualTraceIntegrationTest,SortRuntimeIntegrationTest" test
```

Then run the full suite:

```powershell
mvn "-DforkCount=0" test
```

The runtime tests compare final output for six arrays across all six supported
sorts; dedicated tests also cover descending order, source rewrites, and the
per-step pending/completed operations.

`-DforkCount=0` runs tests in the Maven process. It avoids Mockito self-attach
failures seen with Surefire's separate test JVM in this Windows environment.
