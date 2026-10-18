# Sort Structure Hardening Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` inline. The user authorized `-auto`, so continue through review gates without pausing for approval. Do not commit or push under the user's Git instructions.

**Goal:** Recognize common structure-preserving rewrites of the existing Quick and Merge families without labeling nearby incorrect programs, and keep runtime comparisons truthful.

**Architecture:** Normalize only proven recursive guards and same-class calls in AST matching. Preserve each operation's original AST ID, then evaluate comparisons from actual JDI values using the source operator and operand order. Unsupported partition/merge families remain generic observations.

**Tech Stack:** Java 17, JavaParser, JDI, JUnit 5, Maven, existing HTML/JavaScript UI.

**Spec:** [Sort visualization guide](../../sort-visualization-test-guide.md) and [Merge gate](2026-10-18-merge-sort-gate.md).

## Constraints

- Preserve `/api/debug` and additive `/api/debug/steps` JSON compatibility.
- Accept a rewrite only with a positive test, a nearby negative test, and runtime evidence where the visualization changes.
- Never infer completion from the algorithm label or a truncated snapshot.
- Work in the existing isolated worktree and preserve the current uncommitted Merge changes.

## Task 1 — Recursive method structure

- [x] Add detector tests for `if (low < high) { ... }` in Quick/Merge, `this.sort(...)` for instance Quick, and class-qualified static recursion. Add negatives for an inclusive guard, calls to another receiver, or unrelated statements outside the guard.
- [x] Run the focused tests and observe the expected failures.
- [x] Add a shared strict guard/body normalization; update both detectors while retaining exact operation AST IDs.
- [x] Run focused detector and runtime tests.

## Task 2 — Equivalent comparisons and counter progress

- [x] Add detector tests for reversed comparison operands and Quick's `store = store + 1`, with negatives for a changed operator or wrong index.
- [x] Run tests red; then normalize relational direction and supported increment forms.
- [x] Add runtime tests that compare the displayed decision with observed values for both operand orders and directions; run red then green.

## Task 3 — Evidence and delivery checks

- [x] Check Quick/Merge status remains unknown when runtime evidence is missing or truncated; add a regression only for a reproducible gap.
- [x] Update the guide with newly supported rewrites and remaining family boundaries.
- [x] Check UI JavaScript syntax and local rendering logic without using a blocked browser surface.
- [x] Run focused tests, full Maven suite, targeted formatting checks, `git diff --check`, and one independent read-only review. Report any unverified UI behavior and do not claim universal algorithm recognition.
