# Sort recognition MVP: architecture and delivery scope

Date: 2026-10-13
Status: Proposal for written-spec review. Product implementation has not started.

## 1. User intent and agreed direction

The user uploads Java code written freely, rather than code required to follow a
template. The MVP focuses on sorting; tree algorithms are a later project. The
user selected a hybrid approach: AST analysis, local data-flow analysis, and
runtime corroboration through the existing JDI pipeline. The requested data
forms include primitive arrays, reference arrays, and List/Comparator code.

"All sorting algorithms" is made reviewable as the ten families below, with
explicit variant coverage and mixed/library/unknown outcomes. This is a coverage
target, not a guarantee that arbitrary Java can always receive a unique label.
Unrecognized programs remain valid inputs and receive a reason, rather than a
fabricated algorithm identity.

## 2. Scope challenge and current implementation

Existing modules to retain:

- AstAnalyzer and LocalVariableBindings: source identities, lexical declarations,
  statement ranges, and role binding.
- AlgorithmPatternAnalyzer and SortPatternRegistry: orchestration and the three
  existing Bubble/Selection/Insertion detectors.
- TargetCompiler, JdiSession, BreakpointMapper, and VariableMapper: compilation,
  real runtime observations, and value extraction.
- VisualTraceBuilder, sort trace strategies, DebugStepsResponse, and normalized
  visualization output: existing consumers and compatibility paths.

Current constraints: detectors require canonical syntax and int[]; the registry
selects the first matching detector; SortPattern requires simple indexed
operands and legacy swap metadata; runtime sort strategies mainly interpret
integer-valued elements. These assumptions cannot describe every new family.

Minimal direction: reuse the pipeline and extend recognition incrementally.
Do not create a whole-program compiler, generic graph framework, plugin engine,
or replacement debugger. The complete scope necessarily exceeds five files and
two classes; it is a multi-milestone subsystem, not a small feature. Each
milestone gets a separate bounded spec and implementation plan.

The first milestone design is in
[2026-10-13-sort-semantic-foundation-design.md](2026-10-13-sort-semantic-foundation-design.md).

## 3. Family and data applicability

| Family | Principal structural evidence | Main variant targets |
| --- | --- | --- |
| Bubble | Adjacent comparison and exchange across passes | Early exit, helper swap, forward/backward traversal |
| Selection | Select an extremum in a region and place it at a boundary | Min/max, conditional exchange, helper exchange |
| Insertion | Retain a key, move preceding elements, insert the key | Shift-based and adjacent-swap forms, helper comparison |
| Merge | Process partitions and merge ordered runs | Top-down, bottom-up, merge helper, auxiliary buffer |
| Quick | Partition around a pivot and process the resulting regions | Recursive/iterative, common partition schemes, pivot variants |
| Heap | Build/restore a heap and repeatedly move its extremum | Recursive/iterative sift, min/max heap |
| Shell | Insertion-like processing with changing gaps | Common gap loops, helper insertion pass |
| Counting | Count key frequencies and reconstruct/distribute output | Negative offset, output buffer, stable placement |
| Radix | Multiple key-component passes and redistribution | Common integer digit/bit passes; signed handling must be explicit |
| Bucket | Distribute to buckets, process buckets, concatenate | Numeric bucket mappings and sorting helpers |

Data targets:

- Numeric primitive arrays: byte[], short[], int[], long[], char[], float[],
  double[]. Preserve type-specific semantics and values.
- Reference arrays: boxed values, String, and user objects with a resolvable
  Comparable/comparator or a supported key projection.
- List-based implementations: resolve get/set/add/remove and helpers by symbol
  identity and receiver identity, not by method name alone.
- Ascending, descending, natural, and supported custom order policies.

The family/data matrix is not a Cartesian promise. Counting/Radix need a
supported discrete key representation. Bucket needs a supported bucket mapping.
Unsupported key/order combinations produce explicit applicability diagnostics.
boolean[] has no default natural sorting policy in this project; it requires an
explicit supported ordering rule before verification can be claimed.

Static recognition of a List implementation and extraction of its runtime
contents are separate capabilities. Runtime adapters begin with the existing
tested layouts and expand with dedicated real-JDI fixtures. An arbitrary custom
List is not read by invoking its target methods; unavailable contents are marked
as unavailable. Source support must not be advertised as full runtime support.

TimSort, IntroSort, and user-defined mixtures are composition cases, not aliases
for one constituent family. Emit HYBRID only when constituent families and their
relationship have positive evidence. Otherwise retain multiple candidates or
UNKNOWN. Library calls such as Arrays.sort/List.sort are LIBRARY_SORT with a
resolved call identity; do not invent the library's internal implementation.

## 4. Architecture and module responsibilities

### AST and local semantic analysis

Preserve original AST nodes and their IDs. Build a compact internal view of
sequence accesses, comparisons, writes, exchanges, key retention, index updates,
regions, and calls. Every fact carries source/declaration provenance.

Normalize parentheses and established equivalent forms only where Java type,
evaluation order, side effects, and overflow make the transformation safe. A
pretty-printed expression match is not a substitute for equivalence checks.

Track declarations, receivers, local aliases, and dependencies within a callable.
Follow source-resolved helpers within the uploaded file through bounded method
summaries. Recursion uses a visited/fixed-point budget. Unknown dispatch, external
source, or unresolved side effects remain explicit barriers, not assumptions.
The existing symbol-solver dependency may be configured; no new dependency is
authorized by this proposal.

### Family detectors

Each detector consumes semantic facts and returns candidates with required
evidence, supporting evidence, contradictions, missing facts, source sites,
sequence bindings, and order policy. Adding one family should not require editing
every earlier detector. Selection uses evidence and compatibility; detector order
must not hide genuinely ambiguous matches.

Keep legacy canonical detection available during migration. Map a new candidate
to an existing AlgorithmHint/SortPattern only when its semantics are representable
without false fields. Do not assign fabricated inner loops or swap statements to
Merge/Counting/Radix to satisfy the old record's constructor.

### Runtime corroboration

Use real JDI observations as corroborating evidence for statically bound
operations. Preserve BEFORE_LOCATION and LINE_BREAKPOINT semantics. Snapshot
differences alone do not prove which statement executed, an atomic exchange, or
an algorithm family. No trace on a particular input is not contradictory evidence.

Bind evidence to method invocation, sequence receiver/object identity, thread,
frame context, and source site. Do not share pending operations or baselines
across recursion, repeated calls, or different threads. Shared mutable snapshots
remain non-atomic unless a separately approved capture mechanism establishes more.

Launch/attach changes, additional event capture, termination logic, and exception
diagnostics require their own reviewed JDI slice. No ASM, ByteBuddy, target source
instrumentation, or automatic invocation of arbitrary target comparators/getters.

### Run validation

Recognizing a family is separate from validating one observed execution. Check:

1. Output order under the resolved policy.
2. Preservation of input multiplicities in the designated sort region.
3. Unchanged data outside that region where applicable.

Use an entry baseline and a genuine completion boundary for the particular
invocation. Out-of-place algorithms bind both input and output sequences.
A final displayed line or an apparently sorted intermediate snapshot is not a
completion boundary. Without complete entry/output captures and order evidence,
validation is inconclusive. An incomplete event trace prevents corroboration of
missing operations; it need not invalidate independently complete, trustworthy
entry/output captures when normal completion and isolation are established.
Crash, disconnect, possible concurrent mutation, or missing completion evidence
prevents a passed run-validation claim. Do not rerun an arbitrary uploaded program with
generated inputs automatically; controlled corpus fixtures can be run separately.

Preserve long precision, distinguish char from numeric/string conversions, and
model float/double NaN, infinity, and signed zero explicitly. Primitive
conservation uses an exact type-tagged representation, including floating-point
raw bits when they can be captured reliably; JSON text alone is insufficient.
If the needed representation is unavailable, that check is inconclusive.
Relational operators
and Float/Double.compare are different order policies. Verify user-object
permutation using captured identity/multiplicity, not toString or a guessed equals.
For copied/reconstructed objects, verification requires a proven supported value
projection; otherwise conservation is inconclusive. Null order must be known.

Comparator support expands through explicit summaries of supported key
projections, compare methods, reversals, tie-breakers, and null policies. An opaque,
stateful, or inconsistent comparator may still occur in a recognized algorithm,
but its result cannot be certified by calling it again inside the debugger.

## 5. Result semantics and API compatibility

Two separate result axes:

- Recognition: MATCHED, AMBIGUOUS, UNKNOWN, HYBRID, LIBRARY_SORT. Each candidate
  records its family, source sites, bound sequence, typed order, evidence, and
  limitations. Runtime corroboration is reported independently.
- Validation: PASSED_FOR_THIS_RUN, FAILED_FOR_THIS_RUN, INCONCLUSIVE,
  NOT_EXECUTED. Report order and conservation checks separately with reasons.

MATCHED means the published recognition criteria were satisfied; it is not a
proof of program correctness. A failed sort run may still have a recognized
family. Runtime failure or unavailable values must not change an algorithm label
merely to make the result look successful.

Proposed additive API envelope on POST /api/debug/steps:

```text
sortAnalysis:
  schemaVersion: "1.0"
  recognitions: [identity, source sites, candidate families, status,
                 sequence bindings, order policy, evidence, limitations]
  runs: [recognition identity, invocation identity, execution status,
         corroboration, order check, conservation check, validation status,
         observation sequence references, diagnostics]
```

This is a proposed new contract, not an implemented response. The first API
integration milestone must provide exact records and JSON fixtures for review.

Preserve /api/debug, ObservationResult, existing DebugStepsResponse fields, and
visualization schema 1.0 meanings. Do not silently change legacy confidence scores
into calibrated probabilities. New analysis uses evidence/statuses, and empirical
precision/recall are corpus-level measurements. Existing SortPattern v1 remains
for legacy representable patterns. UI modifications and new visualization
semantics require a dedicated reviewed API/UI slice; recognition can be delivered
through backend results before a specialized animation for every family exists.

The current seven-field ObservationResult versus the five-field AGENTS contract
discrepancy is documented; this project must not resolve it through an unapproved
breaking change.

## 6. Delivery milestones

| Milestone | Deliverable and gate |
| --- | --- |
| R0: runtime evidence reliability | Regression tests and reviewed fixes for try/catch source attribution, main validation, crash diagnostics, preservation of partial observations, and cleanup. Required before stronger runtime validation claims. |
| R1: semantic recognition foundation | The separate first-milestone spec: safe normalization, local data-flow facts, bounded same-file helper summaries, and broader evidence for the existing three families. |
| R2: data and ordering adapters | Typed primitive/reference/List bindings, supported comparator summaries, safe runtime captures, and explicit unsupported cases. |
| R3: comparison families | Merge, Quick, Heap, Shell, including recursion/helper/region binding and ambiguity handling. |
| R4: distribution families | Counting, Radix, Bucket with explicit key-domain applicability and resource bounds. |
| R5: corroboration and validation | Invocation-specific entry/output evidence and typed order/conservation verification; every JDI change receives its own approval and tests. |
| R6: API and MVP release | Exact additive sortAnalysis contract, compatibility fixtures, held-out corpus report, clean package, sample E2E, tester/reviewer sign-off. UI integration is separately reviewed. |

These are delivery boundaries, not a per-file implementation plan. R0 and R1
can develop static foundations independently; R5 depends on R0. Broad data/family
support is not claimed complete when only R1 has shipped. Each implementation
plan should keep changes tied to one milestone rather than refactor unrelated UI.

## 7. Acceptance and evaluation

- Publish a manifest of supported family/variant/data/order combinations and
  applicability constraints. Unsupported cells cannot be counted as supported.
- Each supported cell has positive implementations, structurally different
  implementations, near-miss negatives, and broken/partial-sort cases.
- Metamorphic tests cover renaming, formatting, safe equivalent expressions,
  helper extraction, and supported control-flow variants. Expected classification
  must remain stable when the transformation preserves the relevant behavior.
- Data fixtures cover empty/singleton/sorted/reversed/duplicate/negative values,
  subranges, large/truncated captures, floating-point special values, null policies,
  object ties/identity, and List variants as applicable.
- Runtime fixtures cover class preparation, shadowed locals, genuine multiple
  threads, recursion, crash/disconnect, missing debug metadata, timeout, cleanup,
  repeated invocations, and unavailable/collected objects.
- Test aliasing, overridden helper dispatch, unknown mutation, mixed algorithms,
  dead/unexecuted branches, and unresolved comparator behavior explicitly.
- Hold out implementation sources by author/lineage so renamed copies do not
  inflate generalization metrics. Report precision, recall, unknown/ambiguous rates
  by family and data form; no unmeasured "100%" or fixed-confidence claim.
- Report recognition accuracy separately from runtime validation completeness.
  Final acceptance requires all declared corpus labels and compatibility tests to
  pass; failing or uncovered cells remain documented rather than silently omitted.
- Run TDD for every product slice, focused tests, backend mvn clean package, real
  target E2E, then independent tester and reviewer. No product tests are needed
  for saving this specification alone.

## 8. Scope boundaries and approvals

Java 17 source and one self-contained uploaded file remain the input boundary;
same-file helpers and recursive algorithms are in scope. Current JDI captures
only the selected target class; observing helper classes in the same file needs
a separately reviewed class-prepare/breakpoint scope change. Static helper
recognition may precede runtime coverage for those classes. Multiple-file projects,
external source resolution, attach mode, tree algorithms, automatic target
instrumentation, new backend frameworks, and new dependencies are separate work.

The sandbox/resource limitations of the current target launcher remain a known
deployment concern. This recognition project does not imply secure arbitrary-code
execution or silently install an operating-system sandbox.

The user approved the direction, not code or this newly written spec. Default
interactive mode remains active. Written-spec review precedes writing-plans;
plan review precedes implementation. High-risk JDI and public-contract changes
must be explicit in their milestone specs and approvals.
