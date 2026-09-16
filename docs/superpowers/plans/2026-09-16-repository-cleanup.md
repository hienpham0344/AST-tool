# Repository Cleanup / Structure Implementation Plan

> **For agentic workers:** Execute this plan inline with review checkpoints. Do not commit changes.

**Goal:** Make the repository easier to understand and demo without changing AST/JDI behavior, APIs, Maven layout, or Java packages.

**Architecture:** Keep the existing top-level structure (`backend/`, `UI/`, `docs/`) and the Maven standard layout. Limit changes to correcting stale sample paths in the README, strengthening generated-file ignores, and removing the verified Maven build output under `backend/target/`.

**Tech Stack:** Java 17+, Maven, Spring Boot, JavaParser, `com.sun.jdi`, vanilla HTML/CSS/JavaScript.

**Spec:** User-provided repository cleanup brief in the attached `pasted-text.txt`.

## Global Constraints

- Do not change AST/JDI logic, `ObservationResult`, `ExecutionObservation`, REST endpoints, UI behavior, Java packages, Maven dependencies, or Maven source/test layout.
- Do not delete source, documentation, tests, or examples.
- Preserve all existing working-tree changes and do not commit.
- Use `git mv` only if a verified file move is required; this plan expects no source move because the target structure already exists.

---

### Task 1: Audit and classify repository contents

**Files:**
- Inspect: repository tree, `git status`, `git diff --stat`, `.gitignore`, README files, Java source/tests, and existing docs.

- [x] Confirm the current tree already has `backend/examples/`, Maven standard layout, `UI/`, and `docs/`.
- [x] Confirm `backend/target/` is generated Maven output and no source/doc/test/example duplicate has clear evidence for deletion.
- [x] Search all references to sample paths before editing.

### Task 2: Correct documentation paths and ignore rules

**Files:**
- Modify: `README.md`
- Modify: `.gitignore`

- [x] Replace README command references that assume the working directory contains `examples/` with `backend/examples/`, preserving the existing command context and behavior.
- [x] Add focused ignore rules for generated Java class files, logs, and temporary files without ignoring source, tests, examples, or docs.
- [x] Re-scan the repository for stale `examples/...` paths and inspect the diff for accidental logic changes.

### Task 3: Remove verified build artifacts

**Files:**
- Delete: `backend/target/` (regenerable Maven build/test output only)

- [x] Verify the resolved target is within this repository and contains only Maven-generated output.
- [x] Remove `backend/target/` after the source/reference audit.
- [x] Confirm no tracked source, documentation, test, or example file was removed.

### Task 4: Verify and self-review

**Files:**
- Inspect: final tree, `git diff --check`, and `git status`.

- [x] Run `git diff --check`.
- [x] Run `mvn clean package` from `backend/` and record the test count: 54 tests, 0 failures, 0 errors, 0 skipped.
- [x] Compile and run `SlidingWindowSample.java` from `backend/examples/` with debug information through the CLI pipeline; it returned observation JSON with exit code 0.
- [x] Perform static JavaScript validation appropriate to the existing self-contained `UI/dashboardPage.html`; the single inline script parsed successfully.
- [x] Re-scan references and report remaining cleanup opportunities without expanding scope.
