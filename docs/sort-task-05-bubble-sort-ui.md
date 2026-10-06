# Sort task 05: Bubble Sort step visualization in the UI

## UI behavior

The runtime result panel renders the current observed `int[]` as indexed cells.
Comparison cells, pending write cells, and inferred/completed sorted ranges use
separate colors and a text legend. The current sort phase and comparison result
are shown with the source line and variable table.

The existing Previous/Next controls still move one raw execution snapshot at a
time. Sort responses also provide Play/Pause, a step slider, and 0.5x, 1x, and
2.5x playback speeds. The selected step updates the source highlight, runtime
variables, and sort view together. Changing the file or starting a new run stops
playback.

The renderer labels snapshots as before the source location, shows pending
writes as pending, and only describes a swap as completed when the trace says
`OBSERVED_AFTER_WRITE`. It uses the captured array values and does not animate
synthetic intermediate states.

## Test and review

Backend JDI integration validates the serialized frame inputs, including the
partial array state before the final swap assignment and the later completion.
Browser review on 2026-10-06 used a local fixture matching the API contract:
Next advanced snapshots, the slider sought to a selected step, playback honored
the selected speed and stopped at the last step, and the completed-swap message
appeared once. At 390px and 320px widths, the page stayed within the viewport;
the array remains horizontally scrollable when its values exceed the available
width. Browser console had no errors.

The Spring application could not be kept running for a live browser/API session
in this environment: its JDK 17 Tomcat poller failed to establish an internal
loopback channel. The real JDI and multipart API paths are covered by the
backend integration suite, which passed 139/139 tests; the browser fixture
validates that serialized response shape at the UI boundary.

## Current scope

The renderer consumes `sortFrame` generically; backend trace support currently
starts with Bubble Sort. Selection Sort and Insertion Sort are added in the next
detector tasks and will reuse this renderer.
