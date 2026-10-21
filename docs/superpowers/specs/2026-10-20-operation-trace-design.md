# Operation-level execution trace — design

Status: approved by user on 2026-10-20; explicit clarification: temp must be created and displayed as a distinct intermediate variable entity.

## Required behavior

Every executed source operation participates in a single chronological trace, including ordinary code that has no recognized algorithm. Algorithm hints/visual frames annotate that trace; they never select or replace its steps. Multiple statements on one source line remain distinct. Unexecuted branches are not presented as executed. Comments, blank lines and braces have no fictitious execution; lexical scope and loop boundaries have explicit lifecycle events instead.

For `int temp = a[i]; a[i] = a[j]; a[j] = temp;` with `a=[7,3],i=0,j=1`, show declaration/copy temp=7, first overwrite `[3,3]`, then second overwrite `[3,7]`. Temp is a separate scalar variable holding a copied value, not an inserted array element. Removing temp at scope exit does not remove either array element. If the body repeats, the next temp has the same declaration ID but a new runtime instance ID.

## Current limitation, verified in repository

AstAnalyzer records statements and line-based scopes. BreakpointMapper selects the first executable JDI location and deduplicates other points at that location. ExecutionObservation declares BEFORE_LOCATION and LINE_BREAKPOINT. Consequently snapshot differences alone cannot reconstruct unobserved intermediate writes, evaluate for-header phases, prove the origin of a copied value, or guarantee the final state at a scope exit. Adding events to the existing snapshots alone is insufficient.

## Chosen approach and alternatives

Use AST-guided instrumentation of a temporary compilation copy, selected explicitly by a new trace mode. Keep original source, stable declaration/node IDs, source ranges and diagnostics in all user-facing output. Do not modify uploaded files or use identifier names to recognize behavior. Maintain an explicit original-to-instrumented source/variable map.

Synthetic marker calls provide distinct before/after observation locations. JDI captures the original program frame and filters trace helper calls, variables and synthetic scopes from user-visible execution contexts. A marker must not evaluate any user expression; expressions with side effects must run exactly once in their original order. Conditions/update/initialization require dedicated AST treatment rather than splitting source text on semicolons. No dependency is added; helper source is compiled in the existing isolated temporary target workspace.

Instruction stepping was considered: it exposes compiler instructions and operand-stack changes without reliably mapping each write to a source expression. Pure snapshot-diff enrichment was rejected as the primary source because missing states cannot be recovered honestly. Snapshot differences remain validation for observed effects.

Instrumentation changes timing and stack traces; this trace is for deterministic educational Java, not timing-sensitive/concurrent equivalence. Do not claim arbitrary Java is already covered.

## API compatibility

Proposed request: `POST /api/debug/steps?traceMode=OPERATIONS`, with the existing multipart file field. Omitting traceMode keeps the legacy LINE_BREAKPOINT response and BEFORE_LOCATION semantics. The independent frontend guide must explicitly use OPERATIONS. Legacy `/api/debug` stays unchanged.

Keep top-level `{algorithmHints, steps, warnings}` and add `traceInfo` for the new mode: schemaVersion, mode, granularity, captureStatus and limits. Operation steps have a separately versioned contract; legacy consumers do not silently receive different snapshot phases. Algorithm state builders must not consume AFTER snapshots as if they were legacy BEFORE_LOCATION observations. Adapt by explicit phase or omit unavailable algorithm annotations; ordinary execution steps must remain intact.

## Operation and lifecycle contract

Each dynamic step has sequence, operationId, original AST node ID/source range/code, operation kind, threadId, invocationId, active scope instance IDs, and before/after snapshot references. Operations distinguish declaration, assignment, array write, increment/decrement, condition check, method call/return, and loop init/condition/update. Conditions include the actually observed boolean outcome, never a repeated evaluation. One operation can have several effects without pretending those effects are separate Java statements.

Snapshots distinguish NOT_IN_SCOPE, UNINITIALIZED, AVAILABLE, UNAVAILABLE and TRUNCATED. Missing values are not Java null. Use per-invocation declaration identities plus scope activation IDs for variable instances; array slots use object identity plus index, preserving aliases.

Effects include VARIABLE_CREATED, VARIABLE_INITIALIZED, VALUE_COPIED (only when source and destination are proven/observed), VARIABLE_WRITTEN, ARRAY_ELEMENT_WRITTEN, VARIABLE_DESTROYED, SCOPE_ENTER/EXIT and loop enter/iteration/exit. Each write records old/new values and source/destination references if known. Generic expression results do not invent a copy source. Snapshots carry authoritative values; effects describe the transition and guide animation. No atomic SWAP replaces the three actual operations.

An operation whose after snapshot cannot be captured is PENDING/ABORTED/UNKNOWN, not completed. Method return expressions are evaluated once; finally/abrupt exits cannot be marked complete before their actual control transfer. Exceptions retain observed partial trace with explicit termination status. Never infer successful return, loop completion or scope cleanup from disappearance alone.

## Supported coverage and truthful limits

Implement and test ordinary locals, primitive values, arrays and array writes, sequential statements, nested blocks, if/else, for/while/do-while/enhanced-for, increments, local method calls (including recursion), return, break and continue. Declarations without initializers are represented without fabricating a value. For phases keep init/condition/body/update order, including failed final condition and continue behavior. Scope cleanup must happen on normal and supported abrupt exits; names shared by sibling/nested scopes remain distinct.

Coverage validation runs before starting strict operation mode. Syntax whose semantics cannot yet be preserved (for example unsupported constructor/lambda/try-finally transforms or side-effectful expression rewrites) must fail explicitly with source ranges and an actionable reason. It must never silently fall back to a coarse trace or skip ordinary statements. A source program supported by legacy mode remains runnable there. Exact support is documented from tests rather than claimed for the entire Java language.

Limits for time, steps, memory and projection remain bounded. captureStatus=TRUNCATED/FAILED records the limit/cause and last confirmed operation; frontend must not play a fabricated finish. Budget/phase metadata must distinguish a complete runtime trace from a complete value projection.

## Frontend playback guide to deliver

Create `docs/frontend-operation-trace-guide.md` and update the existing JSON contract/README. Include exact request, versioned schema and complete JSON examples for for phases, same-line temp swap, normal non-algorithm code, repeated local lifetimes, nested scopes/shadowing, aliases, break/continue/return and incomplete traces.

Frontend processes every sequence in order; algorithm hints never filter the sequence. For each operation: highlight original source, render before state, animate confirmed effects, commit after state. A temp is a tile outside the array; copies duplicate the visual value, writes overwrite destination while preserving source, and scope exit removes only that scope's variable instances. Show `[7,3] -> [3,3] -> [3,7]`, not a single swap animation. Pause/step/back/seek rebuild from authoritative snapshots rather than reversing guesses. Unknown/truncated values need distinct rendering, not zero/null defaults. Lifecycle changes must not delete an aliased object still referenced elsewhere.

## Verification and implementation decisions left to the builder

Test instrumentation mapping and emitted trace without relying solely on AST snapshots. Compare instrumented versus original program results/side-effect counts/exception behavior for the supported corpus. Integration tests assert exact operation order and intermediate array values, temp birth/death per iteration, loop phases, method invocations/recursion, unsupported-syntax rejection and budgets. Preserve the existing algorithm/API suite and both trace modes; independent code review before completion. No commit/push without a new user request.

Builder chooses class names, helper transport details and concrete bounds aligned with current JDI constraints. Preserve the Java17 target and installed tools. If a semantic-preserving transform proves unavailable for an advertised construct, implement it or report the coverage restriction before claiming completion; do not replace it with guessed events.
