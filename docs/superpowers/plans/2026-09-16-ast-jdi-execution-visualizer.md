# AST to JDI execution visualizer implementation plan

## Constraints

- Preserve `ObservationResult` and existing `POST /api/debug` response.
- Do not add dependencies, WebSocket, or change JDI launch/attach/poll/resume architecture.
- Keep the existing flat result as a compatibility projection of grouped execution events.

## Order

1. Add RED tests for variable type/scope matching and per-variable JDI failure, then fix
   `AstAnalyzer` and `VariableMapper` without changing public result fields.
2. Add RED tests for physical JDI-location deduplication and multiline source ranges, then fix
   `BreakpointMapper` while preserving repeated breakpoint hits.
3. Add an internal `ExecutionObservation` model. Make `JdiSession` capture one event per
   breakpoint hit, assign a monotonic sequence, preserve statement metadata, and expose the old
   flattened `results()` projection.
4. Add `POST /api/debug/executions` with an explicit additive response DTO containing executions
   and warnings. Keep `/api/debug` and CLI output unchanged.
5. Update the single-file UI to consume grouped executions, navigate Previous/Next, render
   source lines and variable changes, and keep AST/runtime data available without raw JSON as the
   primary view.
6. Add backend and browser-appropriate validation using the Sliding Window fixture. Do not add
   frontend tooling if none exists.

## Verification gates

- Every production behavior change has a failing test first.
- Run focused tests after each phase and `mvn clean package` before completion.
- Run `git diff --check`, inspect the final diff, and verify the old endpoint remains a raw array.
