# Sort task 04: runtime trace for Bubble Sort

## Output

Each applicable `steps[].visualStates[]` entry has an optional `sortFrame`.
It is omitted for other algorithms. `sortFrame.snapshotPhase` is always
`BEFORE_LOCATION`; the frame describes the source operation about to execute
and values observed before that JDI location.

- `phase`: comparison, a pending swap write, a just-observed completed swap, or
  ordinary loop progress.
- `comparison`: two indexes, values from the current array snapshot, and
  `swapRequired` evaluated with the detected direction. This is informational
  evaluation of a side-effect-free condition, not a claim that the branch has
  already executed.
- `pendingMutation`: the current swap assignment and the value it is about to
  write. The frame reports the intermediate duplicate array value during the
  third assignment's BEFORE snapshot.
- `completedMutation`: emitted only at the next observation when the preceding
  snapshot was the final swap write and the new array snapshot confirms the
  temporary value reached the right index.
- `sortedRegion`: suffix implied by the canonical Bubble Sort pass counter.
  Its `INFERRED` status and `CANONICAL_BUBBLE_PASS_COUNT` basis identify this as
  an algorithm invariant, not a separate runtime proof. The first direct
  statement after the loop receives `COMPLETE_AFTER_LOOP`.

If there is no later observation after the final write, the trace deliberately
does not manufacture a completed-swap snapshot. A truncated or unavailable
array cannot produce an observed completed mutation.

## Review and tests

`VisualTraceIntegrationTest.bubbleSortTraceSeparatesPendingWritesFromObservedCompletedSwap`
uses `[2,1]` and real JDI execution. It checks comparison operands, the
intermediate `[1,1]` before the final assignment, the later completed swap, and
the terminal `[1,2]` state.

Run from `backend`:

```text
mvn -Dtest=VisualTraceIntegrationTest test
mvn test
```

Verified 2026-10-06: the focused JDI integration class passed 7/7 tests and the
full backend suite passed 139/139 tests. The API integration test also asserts
that sort frames and their snapshot phase are serialized in `steps`.

## FE handoff

Read `steps[n].visualStates[0].sortFrame` for the current state and
`steps[n].visualStates[0].array.values` for the actual observed array. Keep
the `PENDING_BEFORE_LOCATION` label visible for a write step; use
`OBSERVED_AFTER_WRITE` only for `completedMutation` on a later snapshot.

The terminal frame uses `SORT_COMPLETED` only at the direct next statement
after the detected loop. `COMPLETE_AFTER_LOOP` additionally requires a fully
available, untruncated array; missing data is `UNKNOWN`, and a truncated array
is marked `TRUNCATED`. If target execution ends before another snapshot, the
trace does not claim completion.
