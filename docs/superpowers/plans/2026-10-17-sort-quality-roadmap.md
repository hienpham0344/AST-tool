# Semantic Sort Recognition Roadmap

**Goal:** Make Bubble, Selection, and Insertion Sort a dependable foundation: equivalent changes to Java code structure should still be recognized and visualized, while unrelated code must not be mislabeled. Add Quick Sort and Merge Sort only after this foundation passes its quality gate.

**Scope:** Java 17 source in one uploaded file, initially sorting `int[]` in ascending or descending order. “Structure-independent” means the documented equivalent rewrites below, not every possible Java program. Arbitrary program equivalence cannot be decided reliably from a finite set of examples or runtime executions.

**Current baseline:** The three detectors currently match narrow AST templates. `SortAstSupport.matches` compares expressions with parsed expected expressions; the Bubble and Selection detectors require specific `for` shapes, and Insertion requires a specific `for`/`while` combination. JDI supplies runtime values after an AST hint exists. The UI has specialized views for these three sorts. The test suite could not be run while revising this roadmap because `mvn` is unavailable in this environment.

## Gate 1 — Recognize behavior across source rewrites

### 1. Build a positive and negative source corpus

For each of Bubble, Selection, and Insertion, keep small Java examples that differ in names and structure but perform the same array operations. Include `for`/`while` loop forms, equivalent bounds (`j < n - 1 - pass` versus `j + 1 < n - pass`), increment forms, reversed comparison operands with equivalent direction, braces and local-variable placement, and common algorithm-specific variants such as Bubble's early-exit flag. Include an inline temporary swap and a simple same-file `swap(int[], int, int)` helper as separate levels of support. Record the expected algorithm, direction, operation locations, and trace for every positive example.

Pair each positive example with a close negative: incomplete or wrong swap, wrong array or index, missing progress, a bound that skips elements, conflicting alias, unrelated array write, or an opaque helper call. No negative may produce a sort hint. Rename-only tests remain, but are insufficient on their own.

### 2. Introduce an internal semantic representation

Use JavaParser AST and lexical declaration binding to derive a small internal model of loop initialization, progress, exit condition, array reads/writes, comparisons, and value movement. Normalize equivalent syntax into the same roles and operations. Use local control/data-flow analysis to establish that the compared array elements are the ones swapped or shifted; do not infer this from variable names or final output alone. A same-file helper is eligible only when its array effects can be summarized unambiguously. Unknown calls and unresolved aliases make a candidate unsupported.

Keep this representation internal so the existing REST payloads remain compatible. The [Bubble `while` plan](2026-10-17-bubble-sort-while-recognition.md) is the first vertical slice, not the completion of Gate 1. After validating it, extend the representation and migrate Bubble, then Selection, then Insertion. Keep algorithm-specific rules isolated in their detectors.

### 3. Separate recognition from runtime verification

An AST match identifies an algorithm candidate and source operations. JDI snapshots then attach actual values, check comparison and write transitions, and verify completed sorted regions. A runtime run may disprove or fail to verify a candidate; it must not manufacture a sort label from one successful output. Expose “recognized pattern” separately from “observed/verified execution” in the UI language. Never mark unavailable or truncated arrays as verified.

### 4. Make the visual trace robust to rewritten code

Use operation AST IDs and invocation context rather than fixed source-line order or fixed local names. A supported helper call needs a caller-to-callee operation link so its writes appear on the correct sort step. For each supported rewrite, the UI must show the current array and pointers, pending versus confirmed writes, and the correct source location. Generic runtime variables remain available when no specialized sort pattern is recognized. Document which rewrite forms are supported and why an ambiguous case falls back.

### Gate 1 acceptance matrix

| Dimension | Required evidence |
| --- | --- |
| Algorithms | Bubble, Selection, Insertion; ascending and descending |
| Equivalent rewrites | Renaming; `for`/`while`; counter and bound rewrites; equivalent comparison order; documented algorithm-specific variants |
| Helper calls | Inline swap first; same-file helper only after effect-summary tests pass; opaque calls stay unsupported |
| Input arrays | Empty, one element, sorted, reversed, duplicates, negative values; repeated sort calls in one program |
| Negative examples | Wrong or partial writes, non-equivalent bounds, wrong array, shadowed variables, uncertain aliases, side effects |
| Trace | Comparison operands and writes match snapshots; completed operations require a later confirming snapshot; final order is checked on the actual array |
| User view | Correct highlight, controls, direction, fallback explanation, and JSON export |

**Exit condition:** All published positive rewrites are recognized with the right operation IDs and traces; all close negatives are rejected; the focused detector tests, JDI integration tests, full backend suite, and manual UI checks pass. The guide states the supported equivalence classes and known limits. No known false positive or wrong visual state remains for those classes.

## Gate 2 — Quick Sort

Write a separate design and implementation plan after Gate 1. Begin with a documented `int[]` partition family, such as Lomuto, then add other partition forms as distinct tested equivalence classes. Reuse loop and array-effect semantics, and add recursive invocation identity, pivot, active range, partition comparisons, swaps, and completed partition state. Extend metadata additively; do not force Quick Sort into the existing three-statement-swap `SortPattern` shape. Test multiple recursive calls, repeated invocations, and the Gate 1 array corpus.

**Exit condition:** Every displayed pivot, partition and write corresponds to the right invocation and observed runtime values; the final full-array order is verified. Unknown partition forms fall back to generic runtime observation.

## Gate 3 — Merge Sort

Write a separate design and implementation plan after Gate 2. Start with a documented top-down `int[]` merge family. Model recursive ranges, source and auxiliary arrays, writes into the auxiliary array, copy-back writes, and verified merged ranges. Test odd/even lengths, duplicates, negatives, repeated calls, and the Gate 1 corpus. The UI must not show the source array changing before a copy-back write occurs.

**Exit condition:** Merge frames follow the correct recursive invocation and observed array values; completed ranges and the final full array are verified.

## Execution order

Build and review the source corpus first, then the semantic representation, then one detector and its trace at a time. The [reviewable sprint breakdown](2026-10-17-sort-increments.md) divides this into independently verifiable parts, with a user review after each part and no calendar duration attached. Do not start Quick Sort until Gate 1 is met, or Merge Sort until Gate 2 is met. Keep the public API compatible and the generic runtime view available throughout.
