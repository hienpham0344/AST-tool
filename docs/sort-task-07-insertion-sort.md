# Sort task 07: Insertion Sort

## Detection contract

The detector recognizes the canonical `int[]` pass: save `array[pass]` as a key,
start the scan at `pass - 1`, shift elements while the index is nonnegative and
the value is out of order, then write the key into the gap. The boundary guard
must precede the indexed read, matching Java short-circuit semantics. Ascending
and descending comparisons and reversed comparison operands are supported.

Static metadata names the shift, decrement, and key-write AST nodes separately;
insertion is not represented as a temporary swap. Unsupported or structurally
different code is left unclassified rather than guessed.

## Runtime frame

The state exposes `pass`, `scan`, and `key`. A comparison frame explains whether
the scanned value moves right. At `scan == -1`, it reports that the left boundary
was reached and the key belongs at index zero. Shift and key writes begin as
`PENDING_BEFORE_LOCATION`; a later array snapshot must confirm a write before it
appears as completed. The observed sorted prefix and final array are checked
against runtime values in the detected direction.

## Verification

`InsertionSortDetectorTest` checks renamed bindings, both directions, reversed
operands, unsafe guard order, incorrect shifts, and non-`int[]` arrays.
`VisualTraceIntegrationTest` checks an ascending pass through comparison, shift,
boundary, and key placement, plus descending final-order validation. The
parameterized runtime suite runs all three supported sorts over six different
input shapes.
