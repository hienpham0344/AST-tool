# R2a: static recognition for primitive numeric array sorts

Date: 2026-10-16
Status: approved for auto execution

## Outcome

Extend semantic recognition of Bubble, Selection, and Insertion Sort from
`int[]` to `byte[]`, `short[]`, `long[]`, `char[]`, `float[]`, and `double[]`.
The detector must accept the existing direct, alias, and proven same-file helper
forms only when every array element, temporary, key, helper parameter, and
helper return expression has an exact compatible primitive type.

Each accepted non-`int[]` match is static-only: it returns the existing
`AlgorithmHint` fields, declaration IDs, and evidence, but
`hint.sort()` is `null`. Therefore no specialized sort animation, synthetic
swap, or `SortFrame` is created for it. Existing canonical `int[]` detections
retain `SortPattern` schema v1 and their current runtime visualization.
The detector checks ascending/descending comparison structure internally, but
the current public hint has no typed direction field outside `SortPattern`;
static-only hints do not publish a direction until the later additive API slice.

`boolean[]`, object arrays, nested arrays, widened/boxed helper calls, casts
that change the element type, and unresolved/ambiguous helpers remain unknown.
`float` and `double` fixtures use finite values; this scope recognizes the
strict-comparison implementation, and does not claim a total-order treatment
of NaN.

## Data flow and boundary

```text
AstAnalyzer
  -> CompilationUnit, node/declaration IDs, declared primitive types
  -> SortPatternRegistry.Session
       -> SortSemanticFacts typed bindings
       -> SameFileHelperSummaries exact signatures
       -> Bubble / Selection / Insertion semantic detector
       -> AlgorithmHint (numeric non-int: sort == null)
  -> BreakpointMapper (unchanged source points)
  -> JdiSession (unchanged launch/attach and event collection)
  -> VariableMapper (unchanged primitive-array formatting)
  -> VisualTraceBuilder (existing generic visual state; no SortFrame)
```

The only changed transformation is within `algorithm.sort`. The AST is read,
never rewritten. `BreakpointMapper`, `JdiSession`, `VariableMapper`, public
DTOs, `ObservationResult`, REST/UI behavior, visualization schemas, and Maven
dependencies do not change.

## Type and helper rules

`SequenceBinding` retains the exact declared array type and element type in
addition to declaration identity. Its categories distinguish `INT_ARRAY`,
numeric primitive arrays, `BOOLEAN_ARRAY`, and object/nested arrays. A safe
alias must have the same exact array type as its source.

Index arithmetic remains `int` only. Numeric comparisons accept direct reads
from one proven numeric primitive receiver. Direct temporary swaps and
Insertion keys must use the exact receiver element type. Same-file helpers use
exact types: array references bind to the array type, array elements and value
locals bind to the element type, and positions bind to `int`. No primitive
widening, narrowing, boxing, varargs, or overload guessing can prove a helper.

The legacy `detect(Node)` implementations and `SortAstSupport.intArray` stay
`int[]`-only. The Session path is extended. For an `int[]` direct form the
existing `SortPattern` remains present; for every other numeric primitive the
detector deliberately emits `null` sort metadata even where a direct swap is
visible.

## Acceptance criteria

1. For each of the six added primitive types, direct finite-value Bubble,
   Selection, and shift-based Insertion fixtures return exactly one semantic
   candidate with the correct family and declaration IDs, and
   `sort == null`.
2. A type-correct long helper exchange works for Bubble and Selection; a
   type-correct long comparison helper and adjacent exchange work for
   Insertion. Evidence retains call-site and helper IDs.
3. `boolean[]`, `String[]`, `int[][]`, a mismatched temporary/key, a
   narrowing cast from `long[]`, and a mismatched/widened/ambiguous helper do
   not produce a candidate.
4. Existing int canonical tests still assert non-null v1 `SortPattern`; no
   baseline runtime sort frame regresses.
5. A real compiled JVM target for each six added types completes with one
   generic sort hint and no `VisualState.sortFrame`, including no synthetic
   pending/completed swap. Fixtures use `byte`/`short`/`long`/`float`/`double`
   literals valid for their type and finite values, and valid `char` literals.

## Risks

| Risk | Likelihood × impact | Mitigation |
| --- | --- | --- |
| A generic numeric hint gains SortPattern metadata and produces a false runtime frame | medium × high | Gate SortPattern creation on `INT_ARRAY` only; JVM integration asserts every generic state has `sortFrame == null`. |
| Widening, casts, or overload selection lets an incompatible helper prove a sort | medium × high | Carry exact array/element/scalar types through helper operands; require exact parameter equality and test mismatch/boxing/overload negatives. |
| `boolean[]` is admitted because it shares the existing other-primitive category | medium × high | Split boolean from numeric category and test it at fact and detector levels. |
| Alias provenance loses the original receiver/type | low × medium | Store source and canonical IDs plus exact type; require exact alias type equality. |
| Float/double NaN differs from an application-level total ordering | low × medium | Use finite fixtures and limit the feature claim to strict-comparison implementations. |
| JVM target compile/attach is flaky | low × high | Keep JDI unchanged; use existing `TargetCompiler`/`JdiSession` integration harness with timeouts and report infrastructure failure separately from detector behavior. |

Each phase is independently reversible. Reverting the shared foundation restores
the old type barrier; reverting any detector phase removes only that family;
reverting the final test phase changes no production behavior.
