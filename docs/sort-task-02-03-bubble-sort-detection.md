# Sort tasks 02-03: Bubble Sort hint contract and AST detection

## Scope

`POST /api/debug/steps` adds an `algorithmHints[]` entry with `type: "bubble-sort"`
for a narrow, supported form of Java Bubble Sort over an `int[]`. Existing
algorithm hints and runtime step fields are unchanged. This task does not yet
attach sort `visualStates` or `visualEvents` to execution steps; the hint is
static AST metadata for the trace-building task that follows.

Supported form:

- Two nested forward `for` loops, each with one local `int` counter initialized
  to zero and incremented by one.
- Outer condition `pass < data.length - 1`.
- Inner condition `scan < data.length - 1 - pass`, or the safe but less efficient
  `scan < data.length - 1`.
- An `if` comparing adjacent elements with strict `>` (ascending) or `<`
  (descending).
- A three-statement temporary swap: save the left value, write the right value
  to the left, then write the saved value to the right.
- The array and counters resolve to consistent local/parameter declarations;
  array type must be `int[]`.

Other counter starts, array types, loop forms, non-adjacent comparisons,
non-temporary swaps, helper methods, comparator/library sorts, extra statements
in either loop body, and compound conditional bodies are not supported yet.

## JSON contract

`algorithmHints[]` keeps its existing fields. For a detected sort it has an
additional `sort` object (omitted for non-sort hints):

```json
{
  "type": "bubble-sort",
  "variables": {"array":"data", "pass":"pass", "scan":"scan", "temp":"temp"},
  "variableDeclarationIds": {"array":"ast-Parameter-...", "pass":"ast-VariableDeclarator-...", "scan":"ast-VariableDeclarator-...", "temp":"ast-VariableDeclarator-..."},
  "sort": {
    "schemaVersion": 1,
    "direction": "ASCENDING",
    "innerLoopAstNodeId": "ast-ForStmt-...",
    "comparisonAstNodeId": "ast-BinaryExpr-...",
    "conditionStatementAstNodeId": "ast-IfStmt-...",
    "swapStatementAstNodeIds": ["ast-ExpressionStmt-...", "ast-ExpressionStmt-...", "ast-ExpressionStmt-..."],
    "first": {"indexDeclarationId":"ast-VariableDeclarator-...", "offset":0},
    "second": {"indexDeclarationId":"ast-VariableDeclarator-...", "offset":1}
  }
}
```

`first` and `second` describe array indices as `scan + offset`. AST IDs locate
source constructs; they do not say a comparison or assignment has run. Runtime
states must continue to use each step's `snapshotPhase` and observed variable
values. Consumers should tolerate `sort` being absent and should ignore unknown
future schema versions.

## Review and test

Run from `backend` with JDK 17:

```text
mvn -Dtest=BubbleSortDetectorTest test
mvn -Dtest=MultipartApiIntegrationTest test
mvn test
```

`BubbleSortDetectorTest` checks ascending/descending direction, renamed roles,
declaration IDs, JSON serialization, adjacent indices, and rejection of missing
or extra swap writes, non-strict comparisons, a different array, and non-int
arrays. The API integration test checks that `/api/debug/steps` returns the
structured hint alongside execution snapshots.

Manual check: upload `examples/sort/BubbleSortSample.java`, run `/api/debug/steps`,
then inspect `algorithmHints[0].sort` in Network Preview. At this stage, the FE
can identify the pattern and source lines, but no Bubble Sort animation is
implemented yet.

## Known limits

This is conservative syntax matching, not proof the whole method sorts its
input. The detector does not yet prove array aliasing, sorted-region invariants,
or behavior under external side effects. Task 04 must create states from
runtime snapshots and must not label a swap complete after only one write.

## Verification

Verified 2026-10-04:

- `BubbleSortDetectorTest`: 5 passed.
- `MultipartApiIntegrationTest`: 6 passed, including the real `/api/debug/steps` endpoint.
- Full Maven suite: 138 passed, 0 failures, 0 errors, 0 skipped.
