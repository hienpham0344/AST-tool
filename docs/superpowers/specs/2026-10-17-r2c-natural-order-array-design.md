# R2c: static natural-order recognition for final JDK reference arrays

Date: 2026-10-17
Status: approved for auto execution

## Outcome

Recognize direct Bubble, Selection, and shift-based Insertion Sort over a
one-dimensional `T[]` when `T` is spelled exactly as one of the final JDK
types `java.lang.String`, `java.lang.Byte`, `java.lang.Short`,
`java.lang.Integer`, `java.lang.Long`, `java.lang.Character`,
`java.lang.Float`, `java.lang.Double`, or `java.lang.Boolean`. The comparison
must be a direct `left.compareTo(right)` result compared strictly with literal
zero, allowing zero on either side. Both values must bind to the same safe
array (or the saved Insertion key), with exact type and index provenance.

These final JDK receiver types establish the method identity for compiled
standalone source without changing the parser's symbol resolution only when
the compilation unit contains one top-level class, no other type declarations,
package, imports, inheritance, or anonymous classes. This guard prevents a
source-defined type named `java` from shadowing the JDK package path. Short
imported names, custom `Comparable`, and parameterized elements remain unknown. A positive
match is static-only: the existing hint carries declaration IDs and evidence,
but `sort == null` and no specialized runtime frame. Null values and the
ordering behavior of Float/Double special values are not certified here.

## Conservative boundary

Reject cross-array values, mismatched temp/key type, nested arrays, casts,
non-direct receiver or argument, call chains, helpers, `compareToIgnoreCase`,
nonzero thresholds, non-strict operators, and explicit method type arguments.
Only existing direct Bubble, Selection, and shift-Insertion shapes are in
scope. No JDI launch/attach, ObservationResult, API/DTO, UI, pom, or symbol
solver configuration changes.

## Acceptance

1. A parameterized fact test admits all nine exact fully-qualified element
   types and rejects near misses. Strict comparisons with zero on either side
   preserve normalized operands and operator.
2. Each detector emits one `UNIQUE` static-only candidate with exact array,
   counter, key/temp declaration IDs for a direct fixture. Rejected shapes
   remain `UNKNOWN`.
3. Existing primitive and explicit Comparator recognition tests pass.
4. A real compiled `java.lang.String[]` Bubble target finishes sorted with
   one generic hint and no `VisualState.sortFrame`.

## Data flow

`AstAnalyzer` and `SortPatternRegistry` remain unchanged. `SortSemanticFacts`
adds a narrow natural comparison normalizer, and the three detectors reuse
their existing loop, array, mutation, and identity checks. The runtime
observation path and output schema remain unchanged.
