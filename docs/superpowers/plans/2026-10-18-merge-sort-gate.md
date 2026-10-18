# Merge Sort Gate 3: top-down auxiliary-array family

## Supported source shape

The first Merge family is a non-overridable same-class `void` method with
parameters `(int[] data, int low, int high)` and inclusive bounds. It returns
when `low >= high`, computes `mid = low + (high - low) / 2` (or equivalent
`(low + high) / 2`), then recursively sorts `[low, mid]` and `[mid + 1, high]`.
It allocates an `int[]` auxiliary array of length `high - low + 1`, initializes
left/right cursors to `low`/`mid + 1` and output cursor to zero, and merges
the two halves. An ascending comparison selects `data[left] <= data[right]`;
descending uses `>=`. Each branch writes the selected source value to the
auxiliary array and advances the corresponding cursor. After every choice,
the output cursor advances exactly once. Two remainder loops copy the
unconsumed halves, then a counted loop copies `aux[index - low]` back to
`data[index]` for the whole inclusive range. Counter update spellings and
equivalent `for`/`while` copy-back loops are tested as separate rewrites.

Detector acceptance requires the complete read/write and recursive flow.
Missing copy-back, wrong split, wrong array/index, extra array writes, opaque
helpers, and unresolved aliases fall back to generic runtime observation.
Variable names and wrapper method names do not determine the label.

## Metadata and runtime evidence

`AlgorithmHint.merge` is additive to the existing `sort` and `quick` payloads.
It records direction and operation AST IDs for guard, split, recursive calls,
the choice comparison, writes into `aux`, remainder writes, and copy-back.
The trace uses the existing per-thread invocation IDs and runtime array object
identity. `MergeFrame` contains the active range, midpoint, cursors, auxiliary
snapshot, comparison, pending/confirmed writes, and range/full-array status.

Every displayed write is pending at its source line until a later snapshot
of the same invocation confirms its target value. The main array changes only
when a copy-back write is observed. A completed range is marked verified only
after all its copy-back writes and a complete snapshot pass an order check.
The full array is verified at an observed caller point after the root call.
Missing or truncated arrays remain unknown; no runtime run creates a Merge
label without the structural AST proof.

## Verification order

1. Detector positive, rewritten, and near-negative fixtures fail before code.
2. Implement static metadata and check legacy JSON compatibility.
3. Add runtime cases for empty, singleton, sorted, reversed, duplicates,
   negatives, odd/even lengths, both directions, and repeated calls.
4. Check auxiliary writes, copy-back, recursive IDs, completed ranges, and
   final arrays against JDI snapshots; fix only observed gaps.
5. Check UI/API flows, run the full backend suite and `git diff --check`,
   then review Gate 3 independently.
