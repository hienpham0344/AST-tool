# Prefix Sum Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development task-by-task. No commit/push.

**Goal:** Recognize Prefix Sum structurally and expose truthful line/operation visualization for an external frontend.
**Architecture:** Conservative AST detector emits additive typed metadata. A dedicated trace builder projects existing bounded observations; generic operation effects remain unchanged.
**Tech Stack:** Java17 target, JavaParser, JDI, Spring Boot, Maven/JUnit/AssertJ. No dependencies added.
**Spec:** ../specs/2026-10-21-prefix-sum-design.md — approved 2026-10-21; user requested continuous execution.

## Global Constraints
- Preserve all existing uncommitted operation-trace work; use current semantic-sort-foundation worktree.
- No variable-name-based recognition; declaration identity and direct control flow are mandatory.
- int[]/long[], separate output, SAME_LENGTH and ZERO_SENTINEL, indexed for/while; optional proven zero-sentinel queries.
- Reject unsupported/uncertain candidates; no fabricated completion/value/order.
- LINE_BREAKPOINT uses BEFORE_LOCATION; OPERATIONS keeps generic chronological steps.
- No frontend changes, dependencies, lockfiles, commits or pushes.
- One Maven process at a time. Worktree writes/tests require escalation.

## Review Focus
- Default zero cells must not be marked as already constructed.
- Wrong seed, shadowed names, alias/escape/source mutation must not establish prefix roles.
- Recursion and multiple invocations must not share progress or object identity.
- Query after only conditionally completed construction must not be annotated as proven.
- Unknown/truncated values must not yield invented arithmetic or completion.

### Task 1: AST recognition and metadata
**Files:** new algorithm/prefixsum/PrefixSumDetector.java, model/PrefixSumPattern.java, test algorithm/prefixsum/PrefixSumDetectorTest.java; modify AlgorithmHint.java and AlgorithmPatternAnalyzer.java.
**Interface:** detector detect(CompilationUnit,Node)->Optional<AlgorithmHint>. AlgorithmHint.prefixSum() nullable metadata with schemaVersion, representation, role declaration IDs, seed/recurrence node IDs, optional query descriptors and evidence. Preserve old constructors. Record exact resulting public record contract in ledger for Task2.
- [x] Write failing tests via existing analyzer: renamed roles, both layouts, for/while, int/long, commuted sum, optional query; rejection table for wrong seed/operator/index/bounds, conditional/extra writes, alias/mutation/escape/shadowing and false query proofs.
- [x] Run focused test RED for missing prefix hint, not compile errors.
- [x] Implement conservative detector and additive model/integration. Follow LocalVariableBindings and existing detector precedence.
- [x] Run focused tests plus existing detector regressions GREEN; independent task review, fix material findings.

### Task 2: Runtime frame and trace integration
**Files:** new model/PrefixSumFrame.java, visualization/PrefixSumTraceBuilder.java, test visualization/PrefixSumTraceBuilderTest.java and PrefixSumRuntimeIntegrationTest.java; modify VisualState.java and VisualTraceBuilder.java.
**Interface:** consume AlgorithmHint.prefixSum() from Task1; produce nullable VisualState.prefixSumFrame() preserving constructors. Frame exposes actual array references/projections, recurrence indices, confirmed progress/status and optional query from original source/observations. PrefixSumTraceBuilder follows other builder constructor(hints,nodes) and states(step,point,mainClass) pattern.
- [x] Write tests for seed/pending recurrence, confirmed prefix cells independent of zero values, unavailable/truncated projections, wrong invocation/identity, query bounds; run RED.
- [x] Implement frame/builder and integration using existing array observations, no custom JDI collection. Distinguish pending BEFORE_LOCATION and confirmed effects; guard source node/callable context.
- [x] Real JDI fixture verifies both layouts, negative/zero/int overflow and long preservation; confirmed progress never leaks across calls.
- [x] Run focused model/runtime tests GREEN; independent task review, fix material findings.

### Task 3: API fixtures and frontend handoff
**Files:** test controller/PrefixSumApiIntegrationTest.java; examples/prefixsum/*.java; docs/prefix-sum-frontend-guide.md; docs/examples/prefix-sum-*.json; modify README.md, frontend-runtime-json-contract.md and frontend-operation-trace-guide.md.
**Interface:** POST /api/debug/steps with default legacy mode and traceMode=OPERATIONS. Same prefix hint, legacy frame only in compatible line mode. Export actual JSON from API tests behind -DprefixSum.exportExamples=true.
- [x] API assertions fail before needed integration: hints/frames, unchanged source operation chronology, confirmed array writes, no skipped ordinary statements, optional query metadata.
- [x] Implement missing integration and fixtures. Run focused API tests, export JSON for each mode.
- [x] Write Vietnamese frontend guide: seed/sentinel, addends/destination, confirmed zero versus default, aliases, queries, unavailable/truncated values and overflow; link examples/contracts.
- [x] Review complete feature; resolve findings. Format changed Java with existing formatter CLI; run full suite sequentially, JSON/link validation and git diff --check. Mark plan completed with observed results.

## Commands
From backend, JAVA_HOME=/home/hien/.vscode/extensions/redhat.java-1.58.0-linux-x64/jre/25.0.4.1-linux-x86_64 JAVA_TOOL_OPTIONS=-Dnet.bytebuddy.experimental=true /home/hien/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn -q -Dtest=... test. Logs bounded under /tmp. Existing formatter CLI classpath /tmp/operation-format-classpath; JVM25 requires javac api,file,parser,tree,util,code exports. Do not change formatter dependency versions. Full baseline prior to this feature:458 tests passed.

## Verified completion — 2026-10-21

- Full Maven suite after final review fixes:494tests,0failures,0errors,0skipped; process exit0. Log:/tmp/prefix-full-suite.log.
- Detector, runtime and final integration reviews PASS. Final multi-declarator query regressions reproduced3failures then passed after conservative suppression.
- New Prefix Sum Java files formatted with existing formatter CLI. Actual API JSON refreshed for both modes; JSON and local Markdown links verified; git diff --check run at completion.
- Detailed frontend guide:docs/prefix-sum-frontend-guide.md. No frontend UI changes, dependency changes, commits or pushes.
- Plan scratch reports retained:automatic approval review rejected their deletion; feature delivery does not require deleting these files.
