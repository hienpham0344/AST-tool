# R2a implementation plan: primitive numeric array sort recognition

**Goal:** add static-only semantic recognition for `byte[]`, `short[]`,
`long[]`, `char[]`, `float[]`, and `double[]` in the existing three sort
families, while preserving all `int[]` runtime SortPattern behavior.

**Spec:** [../specs/2026-10-16-r2a-primitive-sort-design.md](../specs/2026-10-16-r2a-primitive-sort-design.md)

## Locked scope

- Do not edit JDI attach/launch, breakpoints, variable mapping, API/UI, DTOs,
  `ObservationResult`, `SortPattern`, or `pom.xml`.
- The existing legacy `detect(Node)` APIs stay int-only. Only the
  session-based semantic path expands.
- Every non-int numeric hint must have `sort == null`, regardless of direct
  exchange/shift source shape. `int[]` direct forms retain v1 SortPattern.
- Use exact primitive identity for arrays, element locals, helper parameters,
  temporary values, and insertion keys. Index parameters remain exactly `int`.
- Preserve unrelated dirty workspace changes and do not share a file between
  phases.

## Dependency graph

```text
Phase 1: exact typed facts + helper summaries
        |
        +----------+-----------+
        |          |           |
Phase 2 Bubble  Phase 3 Selection  Phase 4 Insertion
        \          |           /
         \---------+----------/
                    |
       Phase 5 analyzer + real-JVM regression gate
```

The Session constructs typed facts and helper summaries once per compilation
unit. Each detector consumes those facts to create an `AlgorithmHint`. The
unchanged downstream pipeline consumes that hint: `AstAnalyzer ->
BreakpointMapper -> JdiSession -> VariableMapper`. Since non-int hints carry no
`SortPattern`, the existing visual trace layer remains generic without a new
runtime data path.

## File ownership and rollback

| Phase | Sole owned files | Rollback |
| --- | --- | --- |
| 1 | `SortSemanticFacts.java`, `SameFileHelperSummaries.java`, `SortSemanticFactsTest.java`, `SameFileHelperSummariesTest.java` | Revert this isolated foundation commit; all detector behavior returns to its current int-only barrier. |
| 2 | `BubbleSortDetector.java`, `BubbleSortDetectorTest.java` | Revert only Bubble numeric recognition. |
| 3 | `SelectionSortDetector.java`, `SelectionSortDetectorTest.java` | Revert only Selection numeric recognition. |
| 4 | `InsertionSortDetector.java`, `InsertionSortDetectorTest.java` | Revert only Insertion numeric recognition. |
| 5 | `AlgorithmPatternAnalyzerTest.java`, `visualization/SortRuntimeIntegrationTest.java` | Tests only; production behavior is unaffected. |

No phase may edit another phase's files. Commit each phase after its focused
tests pass, so a later failure can be reverted without pulling down another
family.

## Phase 1 — exact primitive facts and helpers

**Files:** `backend/src/main/java/com/example/astchunker/algorithm/sort/SortSemanticFacts.java`, `backend/src/main/java/com/example/astchunker/algorithm/sort/SameFileHelperSummaries.java`, `backend/src/test/java/com/example/astchunker/algorithm/sort/SortSemanticFactsTest.java`, `backend/src/test/java/com/example/astchunker/algorithm/sort/SameFileHelperSummariesTest.java`

1. RED: add fact tests for each six numeric array types, exact alias retention,
   boolean/object/nested rejection, numeric local comparison, and preserved
   int-only index arithmetic. Add helper tests for exact `long[]` exchange,
   `long` comparison operands, mismatch to `int[]`/`int`, widening/boxing,
   ambiguous overloads, and casts.
2. GREEN: enrich `SequenceBinding` with exact array/element type and split the
   current catch-all primitive category into numeric and boolean. Require exact
   alias type equality. Add typed scalar facts without loosening `IndexOperand`.
   Make helper operand matching compare exact array, element, scalar, and index
   types; require helper exchange temporary type to equal its receiver element.
3. REFACTOR: retain immutable facts and bounded helper recursion/statuses; do
   not change public Session APIs.
4. Run focused tests:
   `mvn -f backend/pom.xml -Dtest=SortSemanticFactsTest,SameFileHelperSummariesTest test`.

Risk is helper false proof (medium × high); exact operand typing and negative
overload tests are the required mitigation.

## Phase 2 — Bubble semantic detector

**Files:** `backend/src/main/java/com/example/astchunker/algorithm/sort/BubbleSortDetector.java`, `backend/src/test/java/com/example/astchunker/algorithm/BubbleSortDetectorTest.java`

1. RED: parameterize canonical direct Bubble fixtures over six numeric types;
   assert `UNIQUE`, exact source declaration IDs, and null `sort`. Add a
   type-correct long helper-exchange and negatives for boolean, mismatched temp,
   and incompatible helper type. Keep canonical int assertions non-null.
2. GREEN: permit numeric sequence bindings in the semantic path; require the
   direct temporary to equal the sequence element type. Build SortPattern only
   for `INT_ARRAY`; helper variants remain static-only as before.
3. REFACTOR: preserve early-exit/mutation checks and existing confidence.
4. Run `mvn -f backend/pom.xml -Dtest=BubbleSortDetectorTest test`.

Risk is an accidental generic SortFrame (medium × high); the null-sort
assertion is mandatory and Phase 5 repeats it on a JVM target.

## Phase 3 — Selection semantic detector

**Files:** `backend/src/main/java/com/example/astchunker/algorithm/sort/SelectionSortDetector.java`, `backend/src/test/java/com/example/astchunker/algorithm/SelectionSortDetectorTest.java`

1. RED: parameterize canonical direct Selection over six numeric types; add
   long helper placement; assert null sort for generic types and retain int
   SortPattern. Reject boolean, object/nested arrays, and temp/helper type
   mismatch.
2. GREEN: accept only typed numeric bindings, require the direct swap temporary
   to equal the array element type, and gate SortPattern on `INT_ARRAY`.
3. REFACTOR: preserve guarded no-op placement and selected-index provenance.
4. Run `mvn -f backend/pom.xml -Dtest=SelectionSortDetectorTest test`.

Risk is a helper exchange inferred across overloads (medium × high); Phase 1's
exact summary and this phase's mismatch test mitigate it.

## Phase 4 — Insertion semantic detector

**Files:** `backend/src/main/java/com/example/astchunker/algorithm/sort/InsertionSortDetector.java`, `backend/src/test/java/com/example/astchunker/algorithm/InsertionSortDetectorTest.java`

1. RED: parameterize shift-based direct Insertion over six types; add a
   type-correct long comparison helper and long adjacent-exchange helper. Assert
   generic null sort and retain int trace metadata. Keep the existing narrowed
   `long[] -> int key` fixture as a rejection; add mismatched key/temp and
   boolean negatives.
2. GREEN: require key and direct adjacent-exchange temporary types to equal the
   receiver element type; consume typed helper operands; gate all SortPattern
   construction on `INT_ARRAY`.
3. REFACTOR: preserve movement-before-decrement, bounds, and mutation checks.
4. Run `mvn -f backend/pom.xml -Dtest=InsertionSortDetectorTest test`.

Risk is accepting a truncating key or comparator (medium × high); exact key and
helper type identity plus the narrowing negative are required mitigation.

## Phase 5 — analyzer dispatch and real-JVM safety

**Files:** `backend/src/test/java/com/example/astchunker/algorithm/AlgorithmPatternAnalyzerTest.java`, `backend/src/test/java/com/example/astchunker/visualization/SortRuntimeIntegrationTest.java`

1. RED: add analyzer-level cases that return exactly one generic hint for a
   numeric primitive fixture and preserve canonical int SortPattern dispatch.
   Add a parameterized Bubble JVM fixture for all six primitive types with
   ordinary finite values and valid char literals.
2. GREEN: only amend tests unless prior phases expose a detector contract bug.
   For each real target, compile with the existing `TargetCompiler`, observe via
   existing `JdiSession`, and assert one hint, target completion, sorted output,
   and `sortFrame == null` for every visual state.
3. Run focused gate:
   `mvn -f backend/pom.xml -Dtest=AlgorithmPatternAnalyzerTest,SortRuntimeIntegrationTest test`.
4. Run the complete required gate:
   `mvn -f backend/pom.xml clean package`.
5. Have an independent tester verify the unit/integration matrix and an
   independent reviewer inspect exact-type checks, generic-null-sort behavior,
   and legacy int regression before completion.

Risk is target compile/attach instability (low × high). Do not alter JDI to
work around it; distinguish infrastructure failure from a detector failure and
use the existing timeout/reporting path.

## Success criteria

- All focused Phase 1–5 commands pass and `mvn -f backend/pom.xml clean package`
  passes.
- The six new types each pass direct semantic recognition in all three
  detectors; helper coverage includes long exchange/comparison paths.
- Every added non-int hint has null `sort`; all six actual JVM fixtures expose
  no `SortFrame` while still completing and sorting their finite inputs.
- Existing int detector and runtime tests pass with non-null v1 SortPattern and
  existing specialized frames.
- Tester and reviewer report no critical or high-severity finding. The final
  diff touches only the ten owned files above.
