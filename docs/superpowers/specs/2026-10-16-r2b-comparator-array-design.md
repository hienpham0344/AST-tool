# R2b: static recognition of reference-array sorts with explicit Comparator

Date: 2026-10-16
Status: approved for auto execution

## Outcome

Recognize direct Bubble, Selection, and shift-based Insertion Sort over a
one-dimensional reference `E[]` when comparison uses a direct parameter
declared exactly `java.util.Comparator<E>`. Require the comparator call to
compare the bound array elements or Insertion key against zero with a strict
`>` or `<` operator. Match the generic argument text exactly to the array
element type. The result is static-only: the existing `AlgorithmHint` has
declaration IDs and evidence, while `sort == null` and no specialized frame
is produced.

Support simple or qualified non-parameterized element types and safe same-type
array aliases. Parameterized element types such as `List<String>[]` require a
later symbol/type slice. Keep primitive-array behavior and the
existing `int[]` runtime trace. The comparator's actual ordering policy may
depend on runtime state and is not certified by this slice.

## Conservative boundary

Reject raw, wildcard, mismatched, imported-short-name, local-alias, field,
and helper comparators; unresolved or overloaded receiver calls; nested
arrays; nonzero comparison thresholds; cross-array operands; and cast or
boxing-based type adaptation. Natural `compareTo`, List mutation, and runtime
order verification are later slices requiring explicit symbol and JDI work.
No dependencies, JDI launch/attach changes, API/DTO changes, or UI changes.

## Acceptance

1. Each family returns one `UNIQUE` candidate for a direct reference-array
   comparator fixture with exact array/counter/key/temp declaration IDs and
   `sort == null`.
2. Reversed comparator operands and strict `<`/`>` forms are normalized for
   structural recognition. The public static-only hint does not expose an
   ordering direction.
3. The negative boundary above returns `UNKNOWN` for matching loop shapes.
4. Existing primitive tests pass. A compiled `String[]` target with a fully
   qualified comparator finishes through the current JVM pipeline with one
   generic hint and no sort frame.

## Module flow

`SortSemanticFacts` binds exact array and comparator parameter declarations,
then normalizes a strict zero comparison with an exact `compare(left,right)`
call. The three detectors reuse their existing loop, mutation, and provenance
checks. `SortPatternRegistry`, AST/JDI mapping, and output schemas remain
unchanged.
