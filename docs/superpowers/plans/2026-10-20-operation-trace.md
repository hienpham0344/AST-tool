# Operation Trace Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans task-by-task. No commits/push without a new user request.

**Goal:** Trace every supported executed source operation and render temporary variables as distinct runtime instances.
**Architecture:** AST instrumentation adds marker calls to a temporary compilation copy; a dedicated JDI collector observes caller frames and a generic transition builder produces operation steps. Existing line-mode JDI and algorithm traces remain compatible.
**Tech Stack:** Java17 target, JavaParser, JDI, Spring Boot, JUnit/AssertJ; existing Maven/JDK25 tools, no new dependencies.
**Spec:** ../specs/2026-10-20-operation-trace-design.md (user approved with explicit requirement that temp is a separate visible variable).

## Global Constraints
- All executed supported ordinary code remains in the chronological trace regardless of algorithm hints.
- No double evaluation, reordering side effects or inferred missing intermediate writes.
- Original source locations and declaration IDs; synthetic helper code/locals hidden.
- Unsupported semantic transforms fail explicitly; limits return incomplete status, never a fabricated finish.
- Old API default LINE_BREAKPOINT remains unchanged; new traceMode=OPERATIONS uses a versioned contract.
- Source/variable/scope instance identities separate recursive calls, threads and repeated body scopes.
- Temp is a separate scalar entity with VARIABLE_CREATED/INITIALIZED, later VARIABLE_DESTROYED; array writes remain distinct.

## Review Focus
- Same-line declarations/writes must have distinct markers and before/after snapshots.
- For continue must execute update; while/do-while conditions and failed final condition retain order.
- Uninitialized locals and shadowed names cannot become null/zero or collide.
- Return/break/continue/exception must unwind only scopes actually exited; helper frames cannot appear as user invocations.
- Copy/array-slot references must use observed identity/index; ambiguous source expressions never invent VALUE_COPIED.

### Task 1: AST instrumenter and static marker metadata
**Files:** new debug/operation/OperationInstrumenter.java, OperationProgram.java, OperationSite.java, OperationCoverageException.java and supporting focused classes if a single transformer becomes unwieldy; tests debug/operation/OperationInstrumenterTest.java.
**Interfaces:** instrument(String source, AstAnalyzer.Analysis original): OperationProgram; OperationProgram(sourceCode,helperMethodName,Map<Integer,OperationSite> sites); OperationSite(markerId,operationId,phase,kind,ObservationPoint source,List<String> visibleDeclarationIds,List<String> scopePath,Map<String,String> attributes). phase BEFORE/AFTER/ENTER/EXIT; kind distinguishes source operations and scopes/loop boundaries. Helper static boolean marker(int id,boolean value) returns its value unchanged; collector reads arguments at helper method entry and snapshots caller frame1. Conditions use nested before/after marker wrappers without repeated evaluation. Scope events use synthetic try/finally as needed, never exposing synthetic scopes/locals. Instrument every supported user method in target class, reject unsupported class/constructor/lambda/exception constructs with source ranges.
- [x] Write failing tests for same-line temp swap marker uniqueness/original mapping, separate var declarations, for phases, if/while/do-while/enhanced-for, nested blocks and abrupt exits.
- [x] Observe red; implement semantic-preserving AST rewrite (not text semicolon splitting), original lexical variable visibility, helper collision avoidance and strict coverage validation.
- [x] Differential compile/run original/instrumented supported samples: same result/side-effect counts, continue, short circuit, recursion and returns. No marker after unreachable statements.
- [x] Focused tests green and independent task review; fix material findings.

### Task 2: Runtime capture and generic effect model
**Files:** new debug/operation/OperationJdiSession.java and operation model records under model/operation; new visualization/operation/OperationTraceBuilder.java; optional TargetCompiler overload only if required; tests OperationJdiSessionTest/OperationTraceBuilderTest.
**Interfaces:** collector observes CompiledTarget+originalAnalysis+OperationProgram and returns raw marker snapshots with context/identity/outcome/termination. Use VariableMapper with allowed original declaration metadata and original line numbers, never map transformed lines to original scopes. Static markers scoped by user invocation; ENTER/EXIT drive runtime scope activation IDs. Dynamic operationId differs each execution even at same source.
- [x] Write unit tests before implementation: source-backed before/after pairing, recursive context isolation, lifecycle created/uninitialized/initialized/destroyed, separate temp entity, copied source/array destination, array intermediate duplicates, missing after and limits.
- [x] Build bounded marker-driven JDI capture: class prepare, helper entry markers, original method entry/exit IDs, exception/timeout/step cap status; dispose VM and do not invoke target methods through JDI.
- [x] Build authoritative snapshots plus effects and operation steps. Preserve explicit unavailable/truncated distinctions and aliases. Do not reuse legacy algorithm state builders against a different phase.
- [x] Focused runtime and model tests green; independent task review.

### Task 3: API, integration fixtures and frontend guide
**Files:** controller/DebugController.java, exception/GlobalExceptionHandler.java as needed; new dto/OperationTraceResponse.java and debug/operation/OperationTraceService.java; docs/frontend-operation-trace-guide.md, docs/frontend-runtime-json-contract.md, README.md; new examples/trace and controller/OperationTraceApiIntegrationTest.java plus real trace integration tests.
**Interfaces:** POST /api/debug/steps?traceMode=OPERATIONS returns algorithmHints, steps, warnings, traceInfo(schemaVersion,mode,granularity,captureStatus,limits). Missing traceMode retains existing DebugStepsResponse. Invalid mode/unsupported coverage has explicit non-2xx error with source location; never silent fallback.
- [x] Integration tests exact temp=7 creation/initialization, [7,3]->[3,3]->[3,7], temp exit/recreation per iteration, for init/condition/update, ordinary statements between algorithm steps, nested/shadow scopes, recursion, supported abrupt exits, unsupported syntax and limits.
- [x] Wire dedicated operation mode, preserve legacy endpoint/API tests, adapt algorithm annotations only when phase is valid.
- [x] Write detailed Vietnamese guide with actual JSON from verified fixtures, entity creation/copy/write/delete rules, order/highlighting, pause/step/back/seek, aliases and incomplete captures. Explicitly prohibit frontend filtering to algorithm-only frames.
- [x] Run focused tests, full suite and diff/JSON/link checks. Independent final integration review; resolve findings before completion.

## Execution
Use existing worktree /home/hien/.codex/worktrees/semantic-sort-foundation/AST-tool (branch codex/semantic-sort-foundation). Worktree writes/tests require escalation. JAVA_HOME=/home/hien/.vscode/extensions/redhat.java-1.58.0-linux-x64/jre/25.0.4.1-linux-x86_64 JAVA_TOOL_OPTIONS=-Dnet.bytebuddy.experimental=true /home/hien/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn -q -Dtest=<focused tests> test from backend. Only one Maven process at a time. Existing JDI tests can hit 10-second timeouts under host load; record failures and verify isolated without silently inflating timeouts.

## Verified completion — 2026-10-21

- Full Maven suite after final review fixes: 458 tests, 0 failures, 0 errors, 0 skipped.
- Independent final fix review: PASS; cumulative payload budgets and abrupt VM termination regressions verified.
- Formatter CLI completed; JSON example regenerated from API capture; local Markdown links and git diff --check verified.
- No commit/push performed for this feature.
