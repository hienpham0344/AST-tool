# Unified visualization UI v1.0

The standalone dashboard consumes the additive `visualization` contract from
`POST /api/debug/steps`. The backend contract is documented in
[visualization-output-v1.md](visualization-output-v1.md).

## Run

Build the backend, start it on its existing port, then open
`UI/dashboardPage.html` directly or through your local static server:

```powershell
mvn -f backend/pom.xml clean package
java -jar backend/target/ast-chunker-0.1.0.jar
```

Select a `.java` file and choose **Quan sát biến runtime**. Structure analysis,
AST generation and JSON export remain separate actions.

## Playback

- Previous/Next, Play, progress slider and speed operate on the returned frame
  array order. A frame's `sequence` is an identity, not its array index.
- The algorithm selector defaults to **All**. A frame can show several cards;
  selecting one algorithm filters cards by its response-local `algorithmId`.
- Source highlighting comes from `frame.source`. The variable table uses the
  legacy observation with the same sequence, preserving its runtime values and
  declaration IDs. An absent match does not borrow another step's variables.
- A selected algorithm can be inactive in the current frame. Source and runtime
  observations remain visible; inactive does not mean finished.
- A new file or run resets playback and algorithm selection.

All six supported types use the same rendering contract: binary search, two
pointers, sliding window, bubble sort, selection sort and insertion sort.

## Reading a card

Array cells show the captured prefix, with the actual runtime length and
truncation status displayed separately. Null elements stay null. Missing,
unsupported and out-of-bounds data is shown as unavailable/status information,
not substituted with zero or fabricated cells.

Pointer labels retain their variable/declaration IDs and status. Cell markers
use current valid captured highlights. Candidate ranges are visual context;
they do not prove accumulator membership or a search-bound convention.

Operations are independent entries. A frame can contain comparison, pending
mutation and observed mutation simultaneously:

- `PENDING`: intended mutation before the suspended location.
- `OBSERVED`: completion reported by the existing runtime strategy.
- `UNKNOWN`: evidence is insufficient, including snapshot comparison operands.

Original kinds, source statuses and provenance remain visible. `ARRAY_CHANGED`
is a snapshot difference, not a confirmed swap. Sorted-region bounds are not
proof of sortedness unless the region status reports verification. Availability
such as `READY` is not an algorithm-completion result.

## Compatibility

If `visualization` is absent, the dashboard uses the existing legacy execution
view. An unsupported schema version is reported explicitly rather than read as
v1.0. IDs such as `hint-0` must not be reused between responses.

## Verification checklist

Run dependency-free UI tests:

```powershell
node --test UI/tests/unified-visualization.test.cjs
```

For browser checks, upload the existing sort samples:

- `examples/sort/BubbleSortSample.java`
- `examples/sort/SelectionSortSample.java`
- `examples/sort/InsertionSortSample.java`

Also check binary-search and two-pointer programs, and
`backend/examples/SlidingWindowSample.java`. Verify controls remain synchronized
with source, arrays and the variable table; selector changes do not seek to a
different frame; partial writes stay pending; and changing files clears old
cards and selection.

Exercise a nested-pattern program to inspect multiple states in a frame, plus
an uncalled helper to inspect inactive metadata. Check empty traces, missing
values, truncated arrays, unsupported versions and legacy responses using
controlled contract fixtures. These checks do not expand backend detector
coverage or claim atomic execution between JDI line breakpoints.

## Verified integration

On 2026-10-12, the UI suite passed 32 tests and the unchanged backend passed
162 tests with `mvn clean package`. Browser uploads exercised all six types,
both sliding-window forms, nested active states and an uncalled helper.
Playback, source/variable joins, JSON export, legacy/version handling, partial
captures, escaping and request/file reset behavior were also checked.

Two initial sample runs returned an empty trace with a target-VM-disconnect
warning; each succeeded on one retry. This UI change does not fix or change
JDI launch/event-loop behavior. Empty results must not be read as a successful
algorithm execution.
