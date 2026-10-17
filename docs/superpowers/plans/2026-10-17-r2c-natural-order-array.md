# R2c implementation plan

Date: 2026-10-17

1. `SortSemanticFacts.java` and `SortSemanticFactsTest.java`: RED parameterized
   allowlist and strict-zero comparison tests plus near misses; GREEN an
   additive normalized natural-order comparison fact with direct call IDs.
2. `BubbleSortDetector.java`, `BubbleSortDetectorTest.java`, and
   `SortRuntimeIntegrationTest.java`: RED/GREEN direct natural `compareTo`
   Bubble with exact roles, safe alias, static-only hint and real JVM String
   fixture.
3. `SelectionSortDetector.java` and `SelectionSortDetectorTest.java`:
   RED/GREEN selected-index comparison using the natural-order fact.
4. `InsertionSortDetector.java` and `InsertionSortDetectorTest.java`:
   RED/GREEN direct key-shift comparison. Keep adjacent exchange unchanged.
5. Run focused tests, tester/reviewer audit, then
   `mvn -f backend/pom.xml clean package`; stage and commit only R2c files.

Facts precede detectors. Each detector phase changes only its own production
and test pair; no JDI, pom, API, UI, ObservationResult, or visualization
schema change is permitted in this slice.
