# Prefix Sum recognition and runtime visualization

Status: approved by user 2026-10-21; execute continuously. Existing operation-trace changes stay intact and uncommitted.

## Intent
Recognize one-dimensional Prefix Sum from structural AST and lexical declaration roles, independent of variable spelling. Expose runtime data for a separate frontend; no bundled UI changes. Preserve every supported ordinary source operation in OPERATIONS mode.

## Initial coverage
- Java primitive int[] and long[] source/result arrays with indexed for or while loops.
- Separate output, same length: p[0]=a[0]; then p[i]=p[i-1]+a[i], starting i=1.
- Separate output, zero sentinel: p[0]=0; then p[i+1]=p[i]+a[i], starting i=0. Permit an implicit sentinel only when a fresh primitive array initialization and no prior writes prove zero.
- Equivalent commuted addition and simple equivalent index expressions may be normalized when structurally proven. No symbolic generalization beyond tested forms.
- Forward single increment, matching source bound, exactly one direct unconditional recurrence per iteration. While update must be unconditional and follow recurrence. No break/continue, conditional recurrence, additional index updates, mutation of source, reassignment/escape of participating arrays, or output/source alias. Reject uncertain candidates rather than guess.
- Bind roles by declaration IDs and direct lexical control flow. Check seed, initialization, bounds, index progression and recurrence together. Negative inputs and zeros remain valid. Numeric overflow follows actual Java int/long values; do not fabricate mathematical results.
- In-place prefix sum, suffix sum, 2D prefix sums, product/XOR scans, library streams and arbitrary step sizes are outside first release.

## Range queries
A query is optional, not required to recognize construction. Annotate a direct expression p[r+1]-p[l] for zero-sentinel layout only when same-array identity, integer index roles, completed construction before query and source AST structure are proven in the same method. Runtime bounds are validated; unknown indices remain unavailable. No claim for same-length conditional query formulas or query loops until separately tested. Return query metadata tied to its source expression, not a guessed algorithm based on subtraction alone.

## Contract and architecture
- Add a conservative PrefixSumDetector using LocalVariableBindings and existing detector conventions. Detector selection must prevent a proven prefix recurrence being mislabeled as a window technique, while retaining existing algorithm regressions.
- AlgorithmHint type prefix-sum, additive nullable prefixSum metadata (schemaVersion=1), representation SAME_LENGTH or ZERO_SENTINEL, role declaration IDs data/prefix/index, seed/recurrence operation node IDs and optional proven query expression IDs. Keep existing constructors/source compatibility.
- LINE_BREAKPOINT: additive prefixSumFrame through visualStates indexed by hintIndex. Frame identifies actual input/output references and projections, current index, recurrence source/target indices, confirmed observed prefix cells and optional query endpoints/result. Respect BEFORE_LOCATION; current line identifies pending work, not a completed write. Never mark cells complete merely because their primitive default is zero. Runtime unavailability/truncation remains explicit.
- OPERATIONS: same prefix-sum hint, preserve generic schemaVersion=2 steps/effects and exact chronology. Existing generic array writes and variable lifecycle are authoritative; no separate animation skips or invented atomic build. The frontend maps recurrence node IDs/source ranges and reference identities to generic steps. Do not attach legacy frames to incompatible phases.
- No custom heap collection needed: use existing bounded primitive array projections. Preserve alias/reference IDs and existing capture budgets.

## Frontend handoff
Update canonical runtime contract and operation guide with a Prefix Sum section; provide docs/prefix-sum-frontend-guide.md and runnable Java fixtures. Explain two arrays, seed/sentinel, highlight addends then destination only after confirmed write, unknown versus confirmed zero, int overflow, and range-query subtraction. Include actual API JSON examples for line mode and operation mode; do not hard-code object IDs. No frontend project changes.

## Verification
Test positive renamed roles, both representations, for/while, commuted addition, int/long, zero/negative values. Reject wrong seed, wrong neighbor/operator/index progression/bounds, conditional/extra writes, alias/source mutation, reassignment/escape, shadowed roles and non-prefix running sums. Query positives and negatives independently test structural proof and runtime bounds. Exercise real JDI/API for seed and successive cells, source/result identities, BEFORE_LOCATION pending state, ordinary operations and intermediate writes. Run focused tests, existing algorithm/API regressions and full suite sequentially. Validate exported JSON, links and git diff --check. Independent implementation review required before completion.

## Builder decisions
Exact immutable DTO names, normalized AST helpers, conservative supported equivalent forms and focused test organization follow project patterns. Do not broaden supported syntax without a structural proof and regression test. Do not commit or push without a new user instruction.
