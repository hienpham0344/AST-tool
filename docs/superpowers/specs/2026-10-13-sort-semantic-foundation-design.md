# Sort recognition R1: semantic foundation for the existing three families

Date: 2026-10-13
Status: Proposal for written-spec review; implementation not authorized yet.

Parent scope:
[2026-10-13-sort-recognition-roadmap-design.md](2026-10-13-sort-recognition-roadmap-design.md).

## 1. Outcome and boundaries

Improve recognition of freely written Bubble, Selection, and Insertion Sort
without replacing the AST/JDI pipeline. The deliverable is broader static
recognition with explicit evidence and conservative ambiguity handling.
This milestone does not claim all ten families or all data forms are complete.

Keep current canonical int[] behavior working. Establish typed sequence and
comparison facts so subsequent data adapters can reuse the analysis. Runtime
verification, new JDI events, dependencies, endpoints, UI edits, and public DTO
changes are outside R1.

## 2. AST and binding responsibilities

Retain original nodes, ranges, declaration IDs, callable IDs, and call-site IDs.
Resolve role references to declarations/receiver identities; renaming alone must
not change the result. Distinguish same-named variables, aliases, different array
receivers, and helper parameters. Unresolved fields or object-instance identity
remain explicit barriers in R1; general field/collection adapters belong to R2.

Extract a compact internal fact representation for comparisons, sequence
reads/writes, temporary values, index updates, conditional guards, loop bounds,
calls, and potential interfering mutations. Use at most two new production
classes for the shared foundation, with nested records as appropriate; reuse
LocalVariableBindings and SortAstSupport where responsibilities still fit.
Further classes need a concrete responsibility and scope justification.

Do not mutate the original AST to normalize it. Initially support:

- Redundant parentheses and formatting differences.
- Equivalent increment/decrement forms with resolved primitive types.
- Reversed comparison operands with the correctly reversed operator.
- Equivalent loop-bound forms only with a safe proof covering evaluation order
  and overflow; an unproved rewrite is rejected or retained as unknown.

No symbolic algebra framework, generic CFG library, global alias solver, or
automatic source rewrite is introduced in this milestone.

## 3. Same-file helper summaries

Resolve source-defined helper calls using the existing JavaParser facilities.
Summarize direct array exchange and supported comparison helpers by their actual
body, receiver, parameters, and source statements. A name such as swap/compare
is never sufficient evidence.

Apply summaries only with a uniquely resolved target and compatible arguments.
Handle bounded helper chains with cycle detection and an explicit analysis
budget. Unknown external calls, dynamic dispatch, escaped aliases, or helper
side effects affecting the sequence/indices are barriers. Keep source provenance
through parameter substitution. Recursion summaries for new families belong to
R3, not to an unrestricted expansion of R1.

## 4. Detector responsibilities

Bubble: require repeated adjacent comparison/exchange with compatible traversal
and bounds; allow supported early-exit flag bookkeeping and helper exchanges.
Flags/breaks must be tied to the pass's exchange result, not ignored indiscriminately.

Selection: require extremum-index selection over a remaining region and placement
at its boundary; allow supported helper exchange and guarded no-op exchange.

Insertion: require retained key and placement after movement, or a supported
adjacent-exchange insertion variant; validate the backward traversal and guards.

Every accepted family includes required facts and absence of known interfering
mutations. Unrelated statements are classified by effects rather than removed by
statement count. Unknown effects prevent unsupported claims. Consider all
compatible candidates internally; registry order is not evidence of uniqueness.

## 5. Legacy integration and honest runtime behavior

Existing AlgorithmHint fields and current canonical fixtures retain their meaning.
Only emit legacy SortPattern metadata when all mandatory fields and operation
sites are representable. A helper swap is not expanded into three fictitious
caller statements. A new static match may have no specialized runtime sort frame
yet; generic observations remain available and limitations are reported through
existing evidence/warnings where suitable. A future additive API envelope belongs
to R6's exact-contract review.

Internal statuses distinguish unique match, ambiguous candidates, and unknown
facts. Do not force a candidate into an existing legacy hint if that erases
ambiguity. Do not change legacy confidence values into a probability estimate.

## 6. Test-first acceptance

For each of the three families, test independently authored canonical variants,
renamed variables, comparison reversal, supported updates, same-file helpers,
early-exit or guarded variants where applicable, and irrelevant statements whose
effects are proven unrelated. Preserve baseline canonical tests.

Negative fixtures include fake swap helper, mutation between comparison and
exchange, wrong receiver, same-name different scope, unsafe bound rewrite,
overridden/unresolved call, incomplete exchange, loop that performs only one pass,
and a program that only searches or partially rearranges the sequence.

Assert classification and evidence source IDs, not only that a hint exists.
Check original AST/source immutability, deterministic output, bounded analysis,
and old DTO/visualization serialization. Ambiguous and unknown cases must have
stable explicit reasons in the internal analysis tests.

Completion requires RED-GREEN-REFACTOR for product slices, focused JUnit tests,
backend mvn clean package, real target E2E for representable legacy paths,
independent tester/reviewer, and a variant coverage table. R1 may report static
recognition for a variant without claiming its runtime visualization is complete.

## 7. Review and next stage

This is the first bounded architectural milestone of the larger sort MVP.
After review, writing-plans will identify exact file ownership, test order, and
execution steps. It must not bundle R0 JDI fixes, new families, generic runtime
adapters, API changes, or UI changes into this milestone.
