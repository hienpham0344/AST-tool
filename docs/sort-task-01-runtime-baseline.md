# Sort task 01: runtime baseline

## Scope

This task establishes actual JDI snapshot behavior before adding sort detection.
It does not implement sort hints, semantic sort events, or an animation renderer.

Supported initial target: explicit Java loops over int[], inside main, with
individual assignments on separate lines. Arrays.sort, helper swaps, custom
comparators, and recursive sorts are outside the first detector's scope.

## Tests

SortRuntimeIntegrationTest runs bubble, selection, and insertion sort against
empty, singleton, sorted, reversed, duplicate, and negative-value inputs.
Expected arrays are computed independently using Java's sorted stream.
Additional tests cover descending bubble sort and a partially completed swap.

For [2,1], the snapshot before the second array assignment contains [1,1],
with temp=2. This must not be presented as an already completed atomic swap.

Run from backend with JDK 17 and Maven:

```text
mvn -Dtest=SortRuntimeIntegrationTest test
mvn test
```

## Manual review

Select examples/sort/BubbleSortSample.java in UI/dashboardPage.html and run
runtime observation. Inspect the steps response in Network Preview. At the
println step, nums should contain [1,2,2,4,5]. All snapshots are BEFORE_LOCATION.
An explicit println after the loop provides a final observation location;
this is not a general solution for methods ending immediately after a write.

## Verification (2026-10-04)

Full Maven suite: 132 tests, zero failures, errors, or skipped tests.
Sort runtime suite: 20 tests passed. No production or frontend code changed.
Task 01 is partial: runtime fixtures are ready, detector negative tests remain
to be implemented alongside the detector. Tasks 02-08 remain pending.

## Remaining acceptance criteria

- Contract: distinguish comparisons about to execute from writes already observed.
- Detector: recognize complete adjacent conditional swaps with lexical identities.
- Negative corpus: reject comparison-only loops, unconditional swaps, incomplete
  swaps, unrelated arrays, and same-name variables from different scopes.
- Trace: never infer completed swap or sorted region from a single assignment.
- UI: consume self-contained states; keep generic runtime fallback.

The negative corpus above is a pending detector requirement, not tested coverage.
