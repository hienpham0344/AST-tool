# Sort task 06: Selection Sort

## Detection contract

The detector recognizes a conservative full-pass shape over an `int[]`: initialize
the selected index from the pass counter, scan the remaining suffix, update the
selected index under a strict comparison, then perform the canonical three-write
temporary swap. The comparison direction determines ascending or descending
order. Variable names are resolved to AST declarations rather than assumed.

Detectors live under `algorithm/sort`; the ordered `SortPatternRegistry` keeps
each algorithm isolated and makes a later detector additive. Runtime behavior is
implemented by the matching strategy under `visualization/sort`.

## Runtime frame

`steps[n].visualStates[m].sortFrame` describes a `BEFORE_LOCATION` snapshot.
Selection frames identify the values compared at `scan` and `selected`, whether
the selected index should change, the pending update, and each pending swap
write. A swap is marked complete only after the following snapshot confirms the
array contents. The sorted prefix is checked against the observed array values;
it is not inferred from the pass counter alone.

At the observation after the loop, the entire captured array is checked in the
detected direction. `VERIFIED_SORTED` means the order check passed. `NOT_SORTED`
includes the first adjacent inversion index and its two values. Missing and
truncated arrays are never reported as verified.

## Verification

`SelectionSortDetectorTest` covers role binding, renamed variables, direction,
near misses, and array type. `VisualTraceIntegrationTest` verifies comparison,
selected-index update, intermediate swap state, observed swap completion, and
terminal order validation. `SortRuntimeIntegrationTest` executes empty,
single-element, already sorted, reverse, duplicate, and negative-value inputs.
