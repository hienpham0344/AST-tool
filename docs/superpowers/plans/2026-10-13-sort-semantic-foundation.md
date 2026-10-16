# Sort Recognition R1: Semantic Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Broaden static recognition of Bubble, Selection, and Insertion Sort written as freely structured, single-file Java `int[]` code while retaining all existing canonical behavior and legacy runtime metadata semantics.

**Architecture:** Build two compact, package-private analysis helpers once per `CompilationUnit`: typed local sequence/counter/comparison facts and bounded same-file helper summaries. A registry-owned public `Session` keeps those helpers inside `algorithm.sort`, evaluates every detector rather than choosing by registry order, and publishes unique legacy-compatible `AlgorithmHint` values, including static-only hints whose `sort` is null. The AST/JDI/visualization pipeline stays unchanged.

**Tech Stack:** Java 17, JavaParser 3.28.2 with existing symbol-solver dependency, Maven, JUnit 5, AssertJ.

**Spec:** [../specs/2026-10-13-sort-semantic-foundation-design.md](../specs/2026-10-13-sort-semantic-foundation-design.md)

## Global Constraints

- Scope is R1 only: Bubble, Selection, and Insertion Sort static recognition; all other families, general data adapters, runtime corroboration, run validation, and tree algorithms are out of scope.
- Keep Java 17 and existing dependencies; do not edit `pom.xml` or add a framework, library, API endpoint, UI behavior, JDI attach/launch behavior, breakpoint mapping, or `ObservationResult`.
- Preserve `AlgorithmHint`, `SortPattern` schema version `1`, `/api/debug`, `/api/debug/steps`, and visualization schema `1.0` meanings; legacy confidence values remain `0.95` Bubble and `0.96` Selection/Insertion.
- Add at most two shared production classes: `SortSemanticFacts` and `SameFileHelperSummaries`, both package-private under `com.example.astchunker.algorithm.sort`; nested records/enums are allowed.
- Analyze only one uploaded compilation unit and resolve only a uniquely identified same-file `static` or `private` helper with compatible `int[]`/`int` parameters. Reject external, virtual/dynamic, overloaded-ambiguous, recursive, cyclic, escaped-alias, or side-effecting helper paths explicitly.
- Do not mutate the original AST. Normalize only redundant parentheses, unit increment/decrement forms, and reversed strict comparisons; accept loop bounds only from the listed structural forms, with no algebraic rewrite or overflow-sensitive equivalence claim.
- A helper-exchange or adjacent-exchange variant may receive a static `AlgorithmHint`, but it must set `hint.sort()` to `null`; never invent caller swap statement IDs, inner-loop IDs, or specialized runtime frame data. Existing generic observations remain unchanged.
- Preserve the dirty visualization baseline and unrelated changes. Do not revert, format, rename, or edit any file outside the owning task.
- Implement each product task RED → GREEN → REFACTOR. If tester/reviewer finds an unexpected regression, stop the auto sequence, report it, and obtain direction before a new repair scope.

## Review Focus

- Same-name declarations in nested/sibling scopes must bind to declaration IDs, not spelling; Task 1 pins this with fact-level shadowing and alias tests.
- An expression such as `right < left` may normalize to the opposite strict relation, while `<=`, `>=`, calls, side effects, and arithmetic-bound rewrites remain rejected; Task 1 pins each case.
- A method named `swap` or `compare` must not count unless a unique safe helper body proves it; Task 1 pins fake, external, overloaded, virtual, cyclic, and budget-exceeded calls.
- A Bubble early-exit flag must be reset once per pass, set only by the accepted exchange, and guard its matching break; Task 2 pins unrelated flag writes and a break without an exchange provenance.
- A recognized new helper/adjacent-exchange variant must remain generic at runtime while canonical fixtures retain their existing visual trace; Task 5 pins both behaviors with a real JVM target.

---

## R1 data flow and dependency graph

```text
AstAnalyzer.parse/analyze
  -> CompilationUnit with original node/declaration IDs
  -> SortPatternRegistry.begin(unit)
       -> SortSemanticFacts.Analysis
       -> SameFileHelperSummaries
  -> Session.evaluate(loop)
       -> Bubble / Selection / Insertion detectors
       -> unique legacy AlgorithmHint only when representable
  -> existing BreakpointMapper -> JdiSession -> VariableMapper
       -> existing VisualTraceBuilder and response schemas
```

`AstAnalyzer` remains the producer of the untouched AST and stable IDs. R1 consumes that AST only in the algorithm package. `BreakpointMapper`, `JdiSession`, and `VariableMapper` receive the same analysis/hints and do not gain events, capture fields, or new value behavior; a static-only hint with `sort == null` therefore yields existing generic observations rather than a claimed sort animation.

```text
Task 1 foundation + helper summaries + session
             |
   +---------+---------+
   |         |         |
Task 2    Task 3    Task 4
Bubble    Selection Insertion semantic detectors
   \         |         /
    \--------+--------/
             |
       Task 5 analyzer integration and JVM compatibility
```

## File ownership and rollback

| Task | Sole owned files | Rollback boundary |
| --- | --- | --- |
| 1 | two new shared classes; `SortPatternDetector`, `SortPatternRegistry`; their new focused tests | Revert Task 1 as one commit; callers still use legacy `detect(Node)` and no detector behavior changes. |
| 2 | `BubbleSortDetector` and `BubbleSortDetectorTest` | Revert its single commit; Session falls back to the legacy Bubble implementation through the default detector method. |
| 3 | `SelectionSortDetector` and `SelectionSortDetectorTest` | Revert its single commit; canonical Selection remains available through legacy detection. |
| 4 | `InsertionSortDetector` and `InsertionSortDetectorTest` | Revert its single commit; canonical Insertion remains available through legacy detection. |
| 5 | `AlgorithmPatternAnalyzer`, analyzer/regression/JVM integration tests | Revert its single commit to restore old registry dispatch; Tasks 1–4 remain isolated and compilable. |

No file is owned by more than one task. A commit is made only after the task's focused tests pass; a failing later task is reverted independently without changing a previous task's files.

## Shared interfaces locked by Task 1

These are the complete new extension points. They deliberately do not expose package-private fact types to `com.example.astchunker.algorithm`.

```java
// package-private, algorithm.sort
final class SortSemanticFacts {
  static Analysis analyze(CompilationUnit unit);

  static final class Analysis {
    Optional<SequenceAccess> sequenceAccess(Expression expression);
    Optional<NormalizedComparison> strictComparison(Expression expression);
    Optional<IndexOperand> indexOperand(Expression expression);
    Optional<Counter> forwardUnitCounter(ForStmt loop);
    Optional<Counter> backwardUnitCounter(ForStmt loop);
    Optional<LoopBound> supportedLengthBound(ForStmt loop, SequenceBinding sequence);
    boolean hasInterferingMutation(Node scope, SequenceBinding sequence, Set<String> allowedAstNodeIds);
  }
}

// package-private, algorithm.sort
final class SameFileHelperSummaries {
  static final int MAX_CALL_DEPTH = 2;
  static final int MAX_VISITED_STATEMENTS = 32;
  static SameFileHelperSummaries analyze(CompilationUnit unit, SortSemanticFacts.Analysis facts);
  Resolution<Exchange> resolveExchange(MethodCallExpr call);
  Resolution<Comparison> resolveComparison(MethodCallExpr call);
}

// public existing interface; default preserves legacy detectors during migration
interface SortPatternDetector {
  Optional<AlgorithmHint> detect(Node loop);
  default Optional<AlgorithmHint> detect(Node loop, SortPatternRegistry.Session session) {
    return detect(loop);
  }
}

// public existing registry, with no package-private types in public signatures
final class SortPatternRegistry {
  Session begin(CompilationUnit unit);
  Optional<AlgorithmHint> detect(Node loop); // retained legacy behavior
  final class Session {
    Evaluation evaluate(Node loop);
    // package-private facts() and helpers() are for detectors only
  }
  record Evaluation(Status status, List<AlgorithmHint> candidates, List<String> limitations) {}
  enum Status { UNIQUE, AMBIGUOUS, UNKNOWN }
}
```

`SequenceBinding` carries both the source declaration ID and canonical local-root declaration ID, display name, and `SequenceKind` (`INT_ARRAY`, `OTHER_PRIMITIVE_ARRAY`, `OBJECT_ARRAY`, `UNKNOWN`). `SequenceAccess`, `NormalizedComparison`, `Counter`, `LoopBound`, and `IndexOperand` carry original AST IDs plus declaration identities. R1 detectors accept only `INT_ARRAY` but retain the type facts for later adapters.

`NormalizedComparison` accepts only strict `<`/`>`; it strips parentheses and reverses both operands and operator together. `IndexOperand` recognizes a bound local with offsets `-1`, `0`, or `+1`. `LoopBound` recognizes only AST-shaped bounds already used by R1 (`index < sequence.length`, `index < sequence.length - 1`, `index < sequence.length - 1 - pass`, and Selection's `scan < sequence.length`). Reversing the operands and relation, such as `sequence.length - 1 > index`, is supported without changing expression evaluation; it never rewrites `<=`, reassociates arithmetic, or assumes overflow-free equivalence.

`SameFileHelperSummaries.Resolution<T>` contains `Status status`, `Optional<T> value`, and immutable limitation strings. Its `Status` is `RESOLVED`, `UNRESOLVED`, `EXTERNAL_OR_DYNAMIC`, `AMBIGUOUS_OVERLOAD`, `CYCLE`, `BUDGET_EXCEEDED`, or `INTERFERING_EFFECT`. `Exchange` and `Comparison` preserve call-site ID, helper callable ID, substituted receiver/index bindings, and actual helper statement IDs. Only a `RESOLVED` summary may support detection.

`Session.evaluate` calls all detectors, deduplicates identical candidates by `(type, patternAstNodeId, sort)`, returns `UNIQUE` for exactly one candidate, `AMBIGUOUS` for two or more distinct candidates, and `UNKNOWN` with stable limitations for none. It does not use detector list order as evidence.

### Task 1: Semantic facts, safe helper summaries, and registry session

**Files:**
- Create: `backend/src/main/java/com/example/astchunker/algorithm/sort/SortSemanticFacts.java`
- Create: `backend/src/main/java/com/example/astchunker/algorithm/sort/SameFileHelperSummaries.java`
- Modify: `backend/src/main/java/com/example/astchunker/algorithm/sort/SortPatternDetector.java`
- Modify: `backend/src/main/java/com/example/astchunker/algorithm/sort/SortPatternRegistry.java`
- Create: `backend/src/test/java/com/example/astchunker/algorithm/sort/SortSemanticFactsTest.java`
- Create: `backend/src/test/java/com/example/astchunker/algorithm/sort/SameFileHelperSummariesTest.java`
- Create: `backend/src/test/java/com/example/astchunker/algorithm/sort/SortPatternRegistryTest.java`

**Consumes:** Existing `LocalVariableBindings`, `AstAnalyzer.nodeId`, current legacy detector implementations, and JavaParser AST only.

**Produces:** The two shared package-private classes and `SortPatternRegistry.Session`; later detector tasks consume `session.facts()` and `session.helpers()` without reconstructing analyses per loop.

- [ ] **Step 1: Write failing fact and session tests.**

  Add assertions that a direct `int[]` parameter and one safe local alias share a canonical declaration ID but retain their source declaration IDs; same-name sibling/nested locals resolve differently; redundant parentheses and reversed `a[j + 1] < a[j]` normalize to one strict comparison; `<=`, `>=`, method calls, assignment expressions, and `j <= length - 2` do not create a supported comparison/bound. Assert a session evaluates all detector candidates, returns `UNIQUE` for one distinct candidate, `AMBIGUOUS` for two deliberately supplied detector doubles, and `UNKNOWN` with a stable limitation for none.

- [ ] **Step 2: Write failing helper-summary tests.**

  Cover a uniquely resolved `private static void exchange(int[] a, int i, int j)` whose body is a real temporary exchange and a private strict comparison helper. Assert provenance includes the caller and helper node IDs after parameter substitution. Assert rejection statuses for a fake swap body, extra sequence/index mutation, same-name shadowed helper parameter, external call, virtual/overridable call, ambiguous overload, direct/indirect cycle, and a chain beyond depth `2` or statement budget `32`.

- [ ] **Step 3: Run the new tests to verify RED.**

  Run: `mvn -f backend/pom.xml -Dtest=SortSemanticFactsTest,SameFileHelperSummariesTest,SortPatternRegistryTest test`
  First establish the expected missing-API compilation failure. Add only minimum declarations/stubs needed for compilation, then run again and record meaningful assertion failures for missing fact/helper/session behavior. Compilation failure alone is not the final RED evidence.

- [ ] **Step 4: Implement the compact fact and helper APIs.**

  Implement exactly the locked interfaces above. Use declaration IDs from `LocalVariableBindings`, type facts from declarations/parameters, and direct local alias chains only. Mark an alias unusable if it is reassigned, escapes to an unsupported call, or its receiver identity cannot be proven. Visit source-defined helpers only when the callable is uniquely selected and `static` or `private`; maintain a visited callable-ID set and the fixed depth/statement budgets. Preserve source AST nodes; report unsupported forms through `Resolution.Status`/limitations instead of guessing.

- [ ] **Step 5: Add the semantic detector bridge without behavior change.**

  Keep `SortPatternDetector.detect(Node)` and `SortPatternRegistry.detect(Node)` intact. Add the default Session overload and `begin(CompilationUnit)`; Session builds facts and summaries once, invokes every detector, and applies the locked unique/ambiguous/unknown rules. Do not modify `AlgorithmPatternAnalyzer` in this task.

- [ ] **Step 6: Run focused tests to verify GREEN.**

  Run: `mvn -f backend/pom.xml -Dtest=SortSemanticFactsTest,SameFileHelperSummariesTest,SortPatternRegistryTest,LocalVariableBindingsTest test`
  Expected: all pass; existing lexical binding behavior remains unchanged.

- [ ] **Step 7: Refactor and commit the isolated foundation.**

  Keep facts immutable, deterministic, and scoped to one callable/unit. Run the same focused command once more, inspect only Task 1 files, then commit:

  ```bash
  git add backend/src/main/java/com/example/astchunker/algorithm/sort/SortSemanticFacts.java backend/src/main/java/com/example/astchunker/algorithm/sort/SameFileHelperSummaries.java backend/src/main/java/com/example/astchunker/algorithm/sort/SortPatternDetector.java backend/src/main/java/com/example/astchunker/algorithm/sort/SortPatternRegistry.java backend/src/test/java/com/example/astchunker/algorithm/sort/SortSemanticFactsTest.java backend/src/test/java/com/example/astchunker/algorithm/sort/SameFileHelperSummariesTest.java backend/src/test/java/com/example/astchunker/algorithm/sort/SortPatternRegistryTest.java
  git commit -m "feat: add bounded sort semantic foundation"
  ```

**Task 1 success criteria:** facts identify direct/aliased `int[]` receivers and strict comparisons by declaration ID; unsafe normalization and every unsupported helper path are rejected with a stable reason; one unit-level Session is sufficient for every loop; existing detector API remains callable.

### Task 2: Bubble semantic recognition and early-exit provenance

**Files:**
- Modify: `backend/src/main/java/com/example/astchunker/algorithm/sort/BubbleSortDetector.java`
- Modify: `backend/src/test/java/com/example/astchunker/algorithm/BubbleSortDetectorTest.java`

**Consumes:** `SortPatternRegistry.Session` from Task 1, including safe sequence/comparison/counter/bound facts and exchange/comparison summaries.

**Produces:** A semantic Bubble candidate that either retains valid direct-swap `SortPattern v1` metadata or publishes a static-only helper variant with `sort == null`.

- [ ] **Step 1: Write failing Bubble variant tests.**

  Add positive fixtures for renamed variables, redundant parentheses, reversed operands with equivalent strict direction, accepted `++i`/`i += 1` updates, a safe local array alias, unrelated scalar logging, a direct canonical swap plus `swapped` early exit, and the uniquely resolved same-file exchange helper. Assert type, direction, original source/declaration IDs, deterministic evidence, and legacy `0.95` confidence. Assert direct canonical/early-exit variants retain exactly their real three swap statement IDs; assert helper variants have `sort == null`.

- [ ] **Step 2: Add failing Bubble negative tests.**

  Reject non-adjacent accesses, a different array receiver, a mutation of the sequence between comparison and exchange, an untrusted equivalent bound, one-pass-only code, a fake helper exchange, an unresolved comparison helper, an alias that escapes, and early-exit code where the flag is reset outside the pass, written outside the accepted exchange branch, or breaks without matching flag provenance.

- [ ] **Step 3: Run the Bubble tests to verify RED.**

  Run: `mvn -f backend/pom.xml -Dtest=BubbleSortDetectorTest test`
  Expected: new variants fail because the canonical three-statement/body-size rule is still the only route.

- [ ] **Step 4: Implement `detect(Node, SortPatternRegistry.Session)` in `BubbleSortDetector`.**

  Retain `detect(Node)` unchanged for canonical compatibility. In the Session overload require two compatible forward counters, trusted Bubble bounds, normalized adjacent strict comparison, repeated traversal, and no interfering sequence/index mutation. Accept only the exact early-exit bookkeeping described in the spec: per-pass false reset, true assignment inside the proven exchange branch, and post-inner-loop break guarded by that same declaration. For direct temporary swaps emit the actual legacy sites; for a resolved helper exchange emit the static hint with `sort == null`, helper/call evidence, and no fabricated runtime fields.

- [ ] **Step 5: Run focused tests to verify GREEN.**

  Run: `mvn -f backend/pom.xml -Dtest=BubbleSortDetectorTest,SortSemanticFactsTest,SameFileHelperSummariesTest test`
  Expected: canonical tests retain their exact metadata; all positive variants classify once; every near miss returns no candidate/unknown rather than Bubble.

- [ ] **Step 6: Refactor and commit Bubble ownership.**

  Ensure legacy and semantic paths share only safe small helpers, with no AST mutation. Commit only Task 2 files:

  ```bash
  git add backend/src/main/java/com/example/astchunker/algorithm/sort/BubbleSortDetector.java backend/src/test/java/com/example/astchunker/algorithm/BubbleSortDetectorTest.java
  git commit -m "feat: broaden bubble sort semantic recognition"
  ```

**Task 2 success criteria:** canonical Bubble JSON-compatible metadata is unchanged; supported aliases/helpers/early exit recognize statically; unsafe effects and unverifiable bookkeeping do not receive a Bubble label; helper matches cannot drive sort-specific runtime frames.

### Task 3: Selection semantic recognition and guarded placement

**Files:**
- Modify: `backend/src/main/java/com/example/astchunker/algorithm/sort/SelectionSortDetector.java`
- Modify: `backend/src/test/java/com/example/astchunker/algorithm/SelectionSortDetectorTest.java`

**Consumes:** Task 1 Session and Task 1 helper summaries; no dependency on Task 2 implementation.

**Produces:** Selection candidates with an extremum-index provenance, either representable direct metadata or a static-only helper exchange hint.

- [ ] **Step 1: Write failing Selection variant tests.**

  Cover min and max variants, renamed variables, reversed strict comparison, `++`/`+= 1` scans, a local alias, irrelevant scalar statements, a real direct exchange guarded by `if (selected != pass)`, and a uniquely resolved helper exchange. Assert the selected-index declaration, comparison/update source IDs, direction, `0.96` confidence, and real direct-swap IDs. Assert a helper-exchange hint is static-only with `sort == null`.

- [ ] **Step 2: Add failing Selection negative tests.**

  Reject an initial selected index not tied to the pass boundary, wrong receiver in comparison/update/exchange, an extremum update outside the comparison branch, mutation of the selected index or sequence between scan and placement, a scan stopping before the remaining region, fake/ambiguous helper exchange, unsafe bound rewrite, and a partial rearrangement that lacks final placement.

- [ ] **Step 3: Run the Selection tests to verify RED.**

  Run: `mvn -f backend/pom.xml -Dtest=SelectionSortDetectorTest test`
  Expected: variants with guards, aliases, helper calls, and noncanonical harmless statements fail before implementation.

- [ ] **Step 4: Implement `detect(Node, SortPatternRegistry.Session)` in `SelectionSortDetector`.**

  Preserve the legacy canonical path. The semantic path must require pass-bound selected initialization, a compatible remaining-region scan, a strict comparison on the same canonical sequence, an update of that selected declaration only in the corresponding branch, and one boundary placement. Permit a no-op guard only when its condition compares the selected and pass declaration identities. Reject unknown mutation. Emit `SortPattern v1` only if all comparison, loop, and direct three-statement exchange sites are actual caller AST sites; otherwise use `sort == null`.

- [ ] **Step 5: Run focused tests to verify GREEN.**

  Run: `mvn -f backend/pom.xml -Dtest=SelectionSortDetectorTest,SortSemanticFactsTest,SameFileHelperSummariesTest test`
  Expected: every specified positive is a single Selection candidate, and every negative remains unknown/no hint.

- [ ] **Step 6: Refactor and commit Selection ownership.**

  Keep direct and helper placement paths visibly separate so metadata cannot leak from helper internals. Commit only Task 3 files:

  ```bash
  git add backend/src/main/java/com/example/astchunker/algorithm/sort/SelectionSortDetector.java backend/src/test/java/com/example/astchunker/algorithm/SelectionSortDetectorTest.java
  git commit -m "feat: broaden selection sort semantic recognition"
  ```

**Task 3 success criteria:** selected-index, region, and placement evidence all share declaration/receiver identity; guarded direct selection remains legacy-representable; unrepresentable helper placement remains static-only and never fabricates trace metadata.

### Task 4: Insertion semantic recognition for shifts and adjacent exchange

**Files:**
- Modify: `backend/src/main/java/com/example/astchunker/algorithm/sort/InsertionSortDetector.java`
- Modify: `backend/src/test/java/com/example/astchunker/algorithm/InsertionSortDetectorTest.java`

**Consumes:** Task 1 Session and summaries; no detector may assume detector registry order.

**Produces:** Shift-and-insert or adjacent-exchange Insertion candidates, while only the existing representable shift shape retains legacy metadata.

- [ ] **Step 1: Write failing Insertion variant tests.**

  Add positive fixtures for renamed variables, parentheses/reversed strict comparisons, `--`/`-= 1`, safe alias, harmless scalar statement, a supported private strict-comparison helper used by the existing shift form, and an adjacent-exchange insertion loop using either direct or resolved-helper exchange. Assert role declaration IDs and `0.96` confidence. Assert canonical shift variants retain actual `shiftWrite`, `scanDecrement`, and `insertWrite` IDs; assert comparison-helper and every adjacent-exchange variant return `sort == null`.

- [ ] **Step 2: Add failing Insertion negative tests.**

  Reject boundary checks ordered after an unsafe array access, forward scan, wrong shift direction, missing final key placement, key/scan aliases rebound before placement, non-adjacent exchange, an external/opaque comparison helper, mutation between movement and placement, unsafe bound algebra, and a loop that only shifts/rearranges a prefix.

- [ ] **Step 3: Run the Insertion tests to verify RED.**

  Run: `mvn -f backend/pom.xml -Dtest=InsertionSortDetectorTest test`
  Expected: helper-comparison, aliases, harmless statements, and adjacent-exchange forms fail under the fixed four-statement canonical rule.

- [ ] **Step 4: Implement `detect(Node, SortPatternRegistry.Session)` in `InsertionSortDetector`.**

  Preserve `detect(Node)` for the current shift-and-insert fixture. The semantic overload must prove forward pass progression, retained key or adjacent pair, backward guarded traversal before array access, same canonical sequence identity for each read/write, and final placement after the movement. A resolved comparison helper may support static classification but has no `SortPattern`; direct adjacent exchange is also static-only. Any unknown write, non-proven guard, or cross-scope binding becomes unknown.

- [ ] **Step 5: Run focused tests to verify GREEN.**

  Run: `mvn -f backend/pom.xml -Dtest=InsertionSortDetectorTest,SortSemanticFactsTest,SameFileHelperSummariesTest test`
  Expected: existing canonical metadata remains exact; safe variants classify once; every unsafe/partial variant has no Insertion hint.

- [ ] **Step 6: Refactor and commit Insertion ownership.**

  Retain the explicit distinction between representable canonical shifts and static-only variants. Commit only Task 4 files:

  ```bash
  git add backend/src/main/java/com/example/astchunker/algorithm/sort/InsertionSortDetector.java backend/src/test/java/com/example/astchunker/algorithm/InsertionSortDetectorTest.java
  git commit -m "feat: broaden insertion sort semantic recognition"
  ```

**Task 4 success criteria:** all accepted movement, comparison, and placement facts share one sequence/receiver identity; canonical traces remain available; helper/adjacent forms are recognized only statically and cannot be interpreted as complete runtime proof.

### Task 5: Analyzer dispatch, compatibility, and real-JVM regression gate

**Files:**
- Modify: `backend/src/main/java/com/example/astchunker/algorithm/AlgorithmPatternAnalyzer.java`
- Modify: `backend/src/test/java/com/example/astchunker/algorithm/AlgorithmPatternAnalyzerTest.java`
- Modify: `backend/src/test/java/com/example/astchunker/algorithm/PatternBindingRegressionTest.java`
- Modify: `backend/src/test/java/com/example/astchunker/visualization/SortRuntimeIntegrationTest.java`

**Consumes:** Tasks 1–4 complete Session overloads and their tested canonical/static-only result distinctions.

**Produces:** One Session per analyzed compilation unit, analyzer output containing only `UNIQUE` sort candidates plus existing non-sort hints, and proof that canonical real-JVM visualization behavior remains intact.

- [ ] **Step 1: Write failing analyzer/compatibility tests.**

  Exercise real analyzer outputs for sources with multiple loops and controlled detector candidates: accept unique sort candidates, leave ambiguous/unknown candidates out of public `List<AlgorithmHint>`, and keep declaration/source identities distinct. Do not use invocation-count spies as a substitute for observable behavior; the single `begin(unit)` allocation is checked by code review. Add deterministic repeated-analysis assertions for a source containing a supported variant plus a same-name shadowed near miss. Extend regression coverage so legacy non-sort algorithms are unchanged. Add JVM fixtures: canonical Bubble/Selection/Insertion still produce their current `SortPattern`-backed visual states; a helper Bubble and adjacent-exchange Insertion produce ordinary generic observations with no `sortFrame`, no synthetic pending/completed swap, and no false `VERIFIED_SORTED` claim generated by sort visualization.

- [ ] **Step 2: Run the integration tests to verify RED.**

  Run: `mvn -f backend/pom.xml -Dtest=AlgorithmPatternAnalyzerTest,PatternBindingRegressionTest,SortRuntimeIntegrationTest test`
  Expected: static variants are not routed through the per-unit Session, or runtime compatibility assertions fail until dispatch is changed.

- [ ] **Step 3: Integrate the Session in `AlgorithmPatternAnalyzer.analyze(CompilationUnit)`.**

  Construct exactly one `SortPatternRegistry.Session` before the loop traversal. Replace only sort dispatch with `session.evaluate(loop)` and add its sole candidate only for `UNIQUE`; preserve the existing binary-search, sliding-window, and two-pointer flow exactly. Do not convert `AMBIGUOUS` or `UNKNOWN` into a legacy hint, warning, confidence change, or API field in R1.

- [ ] **Step 4: Run focused compatibility tests to verify GREEN.**

  Run: `mvn -f backend/pom.xml -Dtest=AlgorithmPatternAnalyzerTest,PatternBindingRegressionTest,SortRuntimeIntegrationTest,BubbleSortDetectorTest,SelectionSortDetectorTest,InsertionSortDetectorTest test`
  Expected: all legacy canonical real-JVM cases retain their visual behavior; static-only variants remain generic; no non-sort detector regression.

- [ ] **Step 5: Run the R1 package gate and full backend verification.**

  Run: `mvn -f backend/pom.xml test`
  Expected: all backend tests pass.

  Run: `mvn -f backend/pom.xml clean package`
  Expected: `BUILD SUCCESS` with tests passing.

- [ ] **Step 6: Refactor, inspect scope, and commit analyzer integration.**

  Run `git diff --check` and inspect the changed-file list against this plan. Do not edit dirty UI/visualization baseline files. Commit only Task 5 files:

  ```bash
  git add backend/src/main/java/com/example/astchunker/algorithm/AlgorithmPatternAnalyzer.java backend/src/test/java/com/example/astchunker/algorithm/AlgorithmPatternAnalyzerTest.java backend/src/test/java/com/example/astchunker/algorithm/PatternBindingRegressionTest.java backend/src/test/java/com/example/astchunker/visualization/SortRuntimeIntegrationTest.java
  git commit -m "feat: dispatch sort recognition through semantic session"
  ```

**Task 5 success criteria:** one compilation-unit Session is used per analysis; first-detector-wins behavior is removed from semantic dispatch; public legacy response types do not change; canonical end-to-end sort traces remain intact; static-only new variants never produce fabricated specialized frames or runtime verification claims.

## Risks and mitigations

| Phase | Risk and impact | Mitigation / stop condition |
| --- | --- | --- |
| Task 1 | Incorrect lexical/alias identity could join two same-named scopes or arrays and create false positives. | Use declaration IDs, test sibling/nested shadowing, accept aliases only when direct/local/non-escaping, return explicit unknown otherwise. |
| Task 1 | Helper resolution could accidentally model dynamic dispatch, cycles, or arbitrary effects. | Accept only unique `static`/`private` helpers, impose depth `2`/32-statement budgets and visited IDs, reject on any unknown effect. |
| Tasks 2–4 | Over-normalized bounds/comparisons could accept unsafe or overflow-different code. | Normalize strict operand reversal and parentheses only; structural bound matching only; preserve unknown on every other form. |
| Tasks 2–4 | New static matches could be mistaken for existing visualized traces. | Emit `sort == null` for helper/comparison-helper/adjacent exchange variants; direct `SortPattern` only from actual caller statements. |
| Task 5 | Changed dispatch could affect non-sort hints or JDI output. | Modify only sort dispatch, pin binary-search/window/two-pointer tests, and run real-JVM canonical sort integration before the full package gate. |
| Any task | Maven/tester/reviewer finds a regression or an unexpected JDI failure. | Stop the auto sequence, classify it against the task diff, and request direction; do not expand R1 into R0/JDI repairs. |

## Measurable completion criteria

- The two-class limit is met and no dependency, API, UI, JDI, `ObservationResult`, or visualization-schema file is changed.
- All specified positive variants for Bubble, Selection, and Insertion produce one deterministic static candidate with declaration/source provenance.
- All specified negative variants are `UNKNOWN`/no public hint, never a false family label.
- Canonical fixtures keep existing types, confidences, variable bindings, `SortPattern v1` sites, and runtime visualization behavior.
- Every helper/comparison-helper/adjacent-exchange variant has `sort == null` and its real-JVM generic trace has no fabricated sort frame or verification claim.
- Focused R1 tests, `mvn -f backend/pom.xml test`, and `mvn -f backend/pom.xml clean package` pass; tester and reviewer sign off, and the variant coverage table records each supported/unknown fixture.

## Execution handoff

The user authorized `-auto` execution for R1 after approving the specifications. Execute Tasks 1–5 sequentially with the required tester/reviewer gates. If a tester or reviewer finds an unexpected issue, stop and report it before attempting a repair; do not begin R2, R0, API/UI, or JDI work under this plan.
