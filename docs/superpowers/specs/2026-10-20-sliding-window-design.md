# Semantic Sliding Window Recognition

## Goal

Recognize fixed-size and variable-size sum windows in Java 17 on `int[]` and
`java.util.List<Integer>` despite renamed variables, `for`/`while` loops and
equivalent accumulator assignments. A hint describes a traversal pattern,
not proof that a problem's answer or complexity is correct.

## Static evidence

`SlidingWindowDetector` binds input, scan/right, optional left, accumulator
and optional size by declaration ID. Scan must advance within the input
bound. Every continuing outer-loop path adds the incoming value at scan
exactly once before removal. A fixed window removes `input[scan - size]`
only under a guard proving that index is valid; size must be immutable and
positive by source precondition. A variable window removes at left, then
advances left under a condition tied to accumulator or span. Incoming and
outgoing values must use the same input. Normalize `+=` and `sum = sum + x`
(and subtraction equivalents). Reject rebinding, shadowing, unrelated
inputs, skipped progress, ambiguous writes, unproved helpers and unsafe
bounds. Sort, Activity Selection and binary search retain precedence;
Two Pointers follows sliding window.

This phase excludes string, frequency-map and deque windows, arbitrary
collection implementations and claimed optimality.

## Runtime and UI

Add nullable `AlgorithmHint.slidingWindow` with kind `FIXED_SIZE` or
`VARIABLE_SIZE`, representation, declaration IDs, operation AST IDs and
source evidence. Add nullable `VisualState.slidingWindowFrame`. A frame
is keyed by hint, thread, invocation and input object IDs, and records
indices, observed values, accumulator, candidate range and effect status.
Before-location snapshots yield `PENDING`; a later same-context observation
may yield `OBSERVED`. Missing, null, truncated or skipped snapshots remain
`UNKNOWN`/`TRUNCATED`. Reuse the existing bounded ArrayList/LinkedList JDI
projection. UI separates source evidence from runtime observation, escapes
all values and keeps the generic variable table.

## Acceptance

Positive and negative AST corpus covers both kinds and representations,
names, loops and update syntax, guarded bounds, branch paths and precedence.
JDI tests cover empty/singleton, null/truncation, missed steps and identity
isolation. API tests ensure additive JSON and unchanged `/api/debug`.
Run focused/full Maven tests, UI `node --check` and `git diff --check`.
