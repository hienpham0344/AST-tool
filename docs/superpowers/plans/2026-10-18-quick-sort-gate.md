# Quick Sort Gate 2: inline Lomuto family

## Supported source shape

The first Quick Sort family is an `int[]` method with array, inclusive `low`,
and inclusive `high` parameters. A guard returns when `low >= high`. The pivot
is copied from `array[high]`, a store index begins at `low`, and a counted
`for` or equivalent `while` scans `[low, high)`. When `array[scan] <= pivot`
(ascending) or `>= pivot` (descending), a proven three-write swap exchanges
`array[store]` and `array[scan]`, then advances `store`. A final proven swap
exchanges `array[store]` and `array[high]`. Calls recurse on
`[low, store - 1]` and `[store + 1, high]`.

This gate will accept only a single same-class method with that complete flow.
Unknown helper methods, altered recursion bounds, non-inclusive ranges, and
unresolved aliases fall back to generic runtime observation. A wrapper may call
the method repeatedly; wrapper shape is not part of recognition.

## Static metadata

Add a Quick-specific nullable metadata object to `AlgorithmHint`, preserving
the existing constructors and `sort` v1 payload. It records declaration IDs
for array, low, high, pivot, store, and scan, plus AST IDs for the guard,
comparison, scan loop, both swaps, and the two recursive calls. Require these
IDs to refer to the matched method, except a summarized non-overridable helper.

## Runtime identity and trace

Add an invocation ID to `ExecutionContext`. JDI method-entry events allocate
an ID per target thread and invocation; method exits retire it. A breakpoint
uses the ID of its suspended top frame. The ID must differ between sequential
sibling calls even at the same stack depth. Old JSON without the field stays
readable.

The Quick frame reports the invocation ID, active inclusive range, pivot value
only after the pivot declaration has executed, scan/store pointers, comparison,
pending and later confirmed swap, and a partition-complete state only when a
later snapshot confirms the final pivot write. Full-array verification occurs
at a wrapper point after the root call or an equivalent observed return state.
The UI must show the active invocation and source line without showing a
pending write as complete.

## Test order

1. Positive and near-negative detector fixtures, including descending and
   equivalent `while` scan; fail before implementation.
2. Detector and metadata JSON tests; run focused detector suite.
3. Recursive invocation ID tests for siblings, repeated roots, and threads;
   fail before JDI changes.
4. Trace tests over empty, singleton, sorted, reversed, duplicates, negatives,
   and repeated calls. Confirm every shown pivot, range, and write from JDI.
5. UI/API smoke checks and full backend suite. Record remaining family limits.
