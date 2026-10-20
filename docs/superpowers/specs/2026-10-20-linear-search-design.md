# Semantic Linear Search Recognition

## Scope

Recognize indexed sequential search over `int[]` and `java.util.List<Integer>` in Java 17. Accept `for` and `while`, renamed variables, reversed equality operands, and methods returning either the found index (`-1` on exhaustion) or a boolean (`false` on exhaustion). Detection describes source behavior, not output correctness for every runtime input.

## Static proof

Bind input, scan index and target by declaration ID. The index starts at zero, advances exactly once per completed iteration, and is bounded by the same input's length. An unconditional per-iteration equality test compares the current element with the target. Its matching branch returns the index or `true`; an immediate return after the loop gives `-1` or `false`. Reject additional index writes, input or target mutations, input aliases that may be written, constructor/helper calls, nested guards, early exits and shadowed bindings. Keep existing hint precedence; run Linear Search after Sliding Window and Two Pointers.

## Runtime and API

Add nullable `AlgorithmHint.linearSearch` and `VisualState.linearSearchFrame`. The frame carries declaration-bound scan/target/input values, input identity, a candidate comparison status, source evidence and snapshot phase. It must not claim that a pre-location comparison executed. Reuse bounded List projection and preserve `/api/debug` shape. Render the current candidate and comparison status in the existing step UI.

## Out of scope

`for-each`, strings, object equality, backward scans, helper-based search and break/flag result flows require separate proof paths. No hint is preferable to an unsupported guess.
