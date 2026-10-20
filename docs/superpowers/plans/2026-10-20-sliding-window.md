# Semantic Sliding Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect and visualize fixed and variable sum windows on `int[]` and `List<Integer>`.

**Architecture:** A dedicated detector produces declaration-bound AST evidence. A dedicated trace builder consumes JDI snapshots. Existing endpoint shapes and hint precedence remain compatible.

**Tech Stack:** Java 17, JavaParser, JDI, Spring Boot, Maven and inline JavaScript.

**Spec:** `docs/superpowers/specs/2026-10-20-sliding-window-design.md`

## Global Constraints

- Preserve uncommitted Two Pointers fixes and unrelated files.
- No new dependencies, commits or pushes without a new explicit request.
- Run Maven with the installed Java 25 JDK and `JAVA_TOOL_OPTIONS=-Dnet.bytebuddy.experimental=true`.

## Review Focus

- A branch skipping the incoming add must receive no hint.
- Shadowed input or accumulator declarations must not be bound to an outer variable.
- The first fixed-window iterations must not access a negative outgoing index.
- Unknown JDI elements and skipped steps must not confirm pending writes.
- Equal-valued but distinct input objects must not share runtime proof.

---

### Task 1: Static detector and metadata

**Files:** Create `algorithm/slidingwindow/SlidingWindowDetector.java`, `model/SlidingWindowPattern.java`, and focused tests; modify `AlgorithmPatternAnalyzer.java`, `AlgorithmHint.java` and analyzer tests.

**Interfaces:** `SlidingWindowDetector.detect(CompilationUnit, Node): Optional<AlgorithmHint>`; the pattern stores kind, representation, declaration IDs, operation AST IDs and evidence.

- [x] Add positive/negative tests for both kinds and representations, including the first three review-focus cases. Run RED.
- [x] Implement role binding, bounds, path progress and add/remove proof. Replace the old inline heuristic only after accepted cases pass. Run GREEN.

### Task 2: Runtime frames

**Files:** Create `model/SlidingWindowFrame.java`, `visualization/SlidingWindowTraceBuilder.java` and tests; modify `VisualState.java`, `VisualTraceBuilder.java`, `IntegerListProjection.java` and JDI integration tests.

**Interfaces:** Frame contains context/input IDs, kind, right/left, optional size, accumulator, range, entering/leaving values, effect status, source evidence and snapshot phase. Statuses: `PENDING`, `OBSERVED`, `UNKNOWN`, `TRUNCATED`, `VIOLATED`.

- [x] Test same-context confirmation, isolation across thread/invocation/object, and unknown/null/truncated/skipped snapshots. Run RED.
- [x] Reuse bounded List projection, bind by declaration IDs, avoid duplicate generic states and confirm effects only after later observations. Run GREEN.

### Task 3: API, UI, examples and verification

**Files:** Modify `MultipartApiIntegrationTest.java`, `UI/dashboardPage.html`, `README.md`; create two Java examples and `docs/sliding-window-test-guide.md`.

- [x] Add API tests for both kinds, nullable new fields and unchanged `/api/debug`. Run RED.
- [x] Render escaped indices, values, range, source evidence and runtime status; keep generic variables. Add compile-ready examples and guide. Run GREEN.
- [x] Run focused and full Maven suites, UI inline `node --check`, `git diff --check`; review false hints and report formatter/JDK limits.
