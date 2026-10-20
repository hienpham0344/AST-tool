# AST → JDI Variable Inspection Tool

This project accepts one Java source file, builds an AST with JavaParser, launches the program
under JDI, and returns the visible local variables at executable source statements.

One variable item in the legacy `/api/debug` payload has this shape:

```json
{
  "astNodeId": "ast-VariableDeclarator-6-11-6-15",
  "variableName": "value",
  "declaredType": "int",
  "runtimeValue": "10",
  "lineNumber": 7
}
```

## Requirements

- JDK 17 or newer (a full JDK is required because the backend invokes `javac` and JDI).
- Maven 3.9 or newer.

## Build and test

From the `backend` directory:

```powershell
mvn clean package
```

This runs the AST, breakpoint mapping, variable mapping, target compiler, JDI integration, and
disconnect-handling tests.

## Run the REST backend

```powershell
cd backend
mvn spring-boot:run
```

The backend listens on `http://localhost:8080`. CORS is enabled for `/api/**` so the standalone UI
can call it from a local file or development server.

All source endpoints accept multipart form data with a field named `file`:

```powershell
curl.exe -F "file=@backend/examples/ScopedVariablesSample.java" http://localhost:8080/api/analyze
curl.exe -F "file=@backend/examples/ScopedVariablesSample.java" http://localhost:8080/api/ast/parse
curl.exe -F "file=@backend/examples/ScopedVariablesSample.java" http://localhost:8080/api/debug
```

- `POST /api/analyze` returns the existing high-level structure chunks.
- `POST /api/ast/parse` returns the structured AST with stable `astNodeId`, `startLine`, and
  `endLine` fields.
- `POST /api/debug` runs the program synchronously and returns `ObservationResult[]` after the
  target exits.
- `POST /api/debug/steps` returns algorithm hints, ordered execution steps, per-step visual
  states/events, and warnings. The independent frontend integration contract is
  [`docs/frontend-runtime-json-contract.md`](docs/frontend-runtime-json-contract.md).

`UI/dashboardPage.html` is a bundled local demo for these endpoints. A frontend
developed in another project should use the runtime JSON contract linked above.

For sorting examples, upload one of `examples/sort/BubbleSortSample.java`,
`examples/sort/SelectionSortSample.java`, `examples/sort/InsertionSortSample.java`,
`examples/sort/QuickSortSample.java`, `examples/sort/MergeSortSample.java`, or
`examples/sort/HeapSortSample.java` and choose
the runtime-variable view. Each sample sorts six arrays in one run; see
[`docs/sort-visualization-test-guide.md`](docs/sort-visualization-test-guide.md) for the step
payload and supported pattern limits.

For BFS, upload `backend/examples/graph/BfsMatrixSample.java` or
`backend/examples/graph/BfsListSample.java`. The runtime frame exposes the graph,
FIFO queue and visited vertices; see [`docs/bfs-test-guide.md`](docs/bfs-test-guide.md).

For Activity Selection, upload `backend/examples/greedy/ActivitySelectionArraySample.java` or
`backend/examples/greedy/ActivitySelectionListSample.java`. The step view separates the source
ordering proof from runtime confirmation; see
[`docs/activity-selection-test-guide.md`](docs/activity-selection-test-guide.md).

For Two Pointers, upload `backend/examples/twopointers/OpposingArraySample.java` or
`backend/examples/twopointers/SameDirectionListSample.java`. The step view shows source evidence
and observed pointer movement for `int[]` and supported `List<Integer>` values; see
[`docs/two-pointers-test-guide.md`](docs/two-pointers-test-guide.md).

For Sliding Window, upload `backend/examples/slidingwindow/FixedWindowArraySample.java` or
`backend/examples/slidingwindow/VariableWindowListSample.java`. The step view shows the
candidate window, accumulator, source evidence and observed add/remove effects for supported
`int[]` and `List<Integer>` sum windows; see
[`docs/sliding-window-test-guide.md`](docs/sliding-window-test-guide.md).

For Linear Search, upload `backend/examples/linearsearch/ArrayIndexSearchSample.java` or
`backend/examples/linearsearch/ListContainsSearchSample.java`. The step view shows the current
candidate comparison for supported indexed searches; see
[`docs/linear-search-test-guide.md`](docs/linear-search-test-guide.md).

## Run the CLI pipeline

`Main` accepts the path to a Java source file. Maven can run it without adding an extra build
plugin:

```powershell
cd backend
mvn -q "-Dexec.mainClass=com.example.astchunker.Main" "-Dexec.args=examples/ScopedVariablesSample.java" org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```

The JSON array is printed to standard output; non-fatal JDI warnings are written to standard error.

## Debug target rules

The current launch-only flow supports one self-contained Java source file with a top-level class
that declares `public static void main(String[] args)`. The backend compiles it in an isolated
temporary directory with:

```text
javac -g --release 17
```

If you compile a target manually for inspection or a future attach-mode extension, retain debug
metadata with:

```powershell
javac -g -d out examples/ScopedVariablesSample.java
```

JDI uses `ClassPrepareRequest` before installing breakpoints, maps visible variables using their AST
scope ranges, reads the event thread's own stack frame, and exits cleanly on VM death or disconnect.
Complex objects are represented as `fully.qualified.Type#referenceId` rather than invoking target
code through `toString()`.

The REST endpoint has a 30-second target timeout. WebSocket streaming is intentionally deferred;
the TODO is retained beside the synchronous REST controller for a later real-time UI phase.
