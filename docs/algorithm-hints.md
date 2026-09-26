# Algorithm hints API (step 1)

`POST /api/debug/steps` accepts the existing multipart Java `file` and now returns
`algorithmHints` alongside the existing `steps` and `warnings`. `/api/debug`
continues to return the legacy observation array.

Step 1 defined the contract only. Step 2 adds conservative AST detection rules;
see [the step 2 review](step-02-ast-algorithm-detection.md). An empty list means no available suggestion; it
does not mean the source contains no algorithm. The frontend should continue
rendering structured variables when no hints are available.

Example of a populated hint (illustrative):

```json
{
  "type": "binary-search",
  "confidence": 0.9,
  "visualPlan": "array-pointers",
  "evidence": ["Midpoint narrows the search interval"],
  "variables": {"array": "nums", "left": "lo", "right": "hi", "mid": "m"},
  "startLine": 3,
  "endLine": 12
}
```

- `type`: algorithm pattern identifier.
- `confidence`: finite heuristic score in [0, 1], not a calibrated probability.
- `visualPlan`: suggested renderer identifier. Unknown identifiers should fall
  back to the existing variable renderer.
- `evidence`: explanations supporting the suggestion.
- `variables`: semantic role to source variable name mapping. Names apply within
  the hint's source range, not globally across methods.
- `startLine`, `endLine`: inclusive, one-based source range.

Step 4 adds per-step visualization states and events; see
[the visualization contract and review](step-04-visualization-contract.md).
Next steps: frontend rendering and additional algorithm families. Hints describe source
patterns and do not prove correctness or that a code path was executed.
