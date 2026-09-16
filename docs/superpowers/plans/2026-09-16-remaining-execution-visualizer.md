# Remaining AST-JDI Execution Visualizer Phases Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete breakpoint correctness, grouped execution observations, an additive execution-step API, the existing static UI visualizer, and real Sliding Window end-to-end verification.

**Architecture:** Keep `JdiSession.DebugRun.executions` as the internal source of truth. Map each physical JDI `Location` to at most one observation point, capture one `ExecutionObservation` per breakpoint event, preserve `/api/debug` as the flattened compatibility projection, and add `/api/debug/steps` for grouped frontend consumption. Extend the existing single HTML page without new frontend dependencies.

**Tech Stack:** Java 17, Spring Boot, JavaParser, `com.sun.jdi`, Maven, vanilla HTML/CSS/JavaScript.

**Spec:** User-approved remaining-phases request in the conversation attachment dated 2026-09-16.

## Global Constraints

- Do not change `ObservationResult` fields or the existing `/api/debug` response shape.
- Do not change JDI launch, attach, event-loop, VM lifecycle, suspend, or resume architecture.
- Do not add Maven/NPM dependencies, WebSocket, or a new frontend framework.
- Preserve raw grouped snapshots internally and expose old flat results through `DebugRun.results()`.
- Use the existing target compiler and UTF-8 single-file source workflow.
- No commit.

### Task 1: Lock Phase 2B breakpoint mapping

**Files:** Modify `BreakpointMapper.java`; test `BreakpointMapperTest.java` and `JdiSessionIntegrationTest.java`.

- [ ] Add RED tests for same-line nested statements, duplicate physical locations, multiline ranges, and helper-method locations.
- [ ] Run the focused breakpoint tests and verify failures are caused by mapping behavior rather than test syntax.
- [ ] Implement only physical `Location` deduplication and source-range lookup needed by those failures; retain repeated event hits.
- [ ] Run focused unit and JDI integration tests.

### Task 2: Verify and complete the internal execution model

**Files:** Inspect and modify only `ExecutionObservation.java`, `JdiSession.java`, and related tests if a failing invariant requires it.

- [ ] Add RED tests for monotonic sequence, repeated same line/location, zero-visible-variable events, grouped variables, and event-thread frame selection.
- [ ] Keep one `ExecutionObservation` per breakpoint event and preserve event order.
- [ ] Keep `DebugRun.results()` as the exact flattening adapter consumed by the old CLI/controller.
- [ ] Run focused JDI tests and verify no launch/event-loop changes were introduced.

### Task 3: Add the additive execution-step API

**Files:** Create a response DTO under `backend/src/main/java/com/example/astchunker/dto/`; modify `DebugController.java`, `MultipartApiIntegrationTest.java`, and add API tests as needed.

- [ ] Add a RED MockMvc test for `POST /api/debug/steps` returning grouped steps, variables, and warnings without repeating the AST.
- [ ] Implement `POST /api/debug/steps` using the existing source reader, analyzer, compiler, and JDI session.
- [ ] Return an additive response containing `steps` and `warnings`; leave `/api/debug` returning the raw `ObservationResult[]` array.
- [ ] Run API regression tests for both endpoints.

### Task 4: Implement the existing-page execution visualizer

**Files:** Modify only `UI/dashboardPage.html`.

- [ ] Add source-line rendering with line numbers and current-line highlighting.
- [ ] Call `/api/debug/steps` for visualizer mode while retaining existing AST/analyze actions.
- [ ] Add Previous/Next controls and a `Step N / total` indicator.
- [ ] Render grouped variables and deterministic changes by comparing the prior step by variable name and AST declaration id.
- [ ] Keep raw JSON export available and escape all source/runtime content before HTML insertion.
- [ ] Verify the page with the current browser/static-page tooling; do not add dependencies.

### Task 5: Run full Sliding Window E2E verification

**Files:** Create `backend/examples/SlidingWindowSample.java` only if no existing fixture can serve; otherwise use the existing fixture. Add or modify tests only for verified missing coverage.

- [ ] Run the real target through the existing CLI/JDI path and verify final output/state includes array contents, loop locals, repeated lines, and `maxSum = 9`.
- [ ] Run both REST endpoints against the running backend and verify old flat-array and new grouped-step responses.
- [ ] Exercise the UI with the same file and verify source highlighting, Previous/Next, changed variables, repeated lines, and arrays.
- [ ] Run fresh `cd backend; mvn clean package`, inspect `git diff --check`, and record `git status`.

## Verification gates

- Every production behavior change has a failing test first.
- Run focused tests after each task and a fresh full build after the final task.
- Treat any required public-contract break, dependency, WebSocket, destructive file operation, or JDI lifecycle change as a hard stop.
