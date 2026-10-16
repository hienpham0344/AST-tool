# R2b implementation plan

Date: 2026-10-16

1. `SortSemanticFacts.java` and `SortSemanticFactsTest.java`: RED tests for
   exact `java.util.Comparator<E>` parameter, one-dimensional `E[]`, safe
   alias, strict zero comparison, and near-miss rejection. GREEN with typed
   comparator binding and comparison normalization; keep primitive facts.
2. `BubbleSortDetector.java` and `BubbleSortDetectorTest.java`: RED/GREEN
   comparator-guarded adjacent exchange, exact roles and static-only hint.
3. `SelectionSortDetector.java` and `SelectionSortDetectorTest.java`: RED/GREEN
   comparator-guarded extremum update and boundary exchange.
4. `InsertionSortDetector.java` and `InsertionSortDetectorTest.java`: RED/GREEN
   comparator-guarded key shift; reject wrong key or array provenance.
5. `SortRuntimeIntegrationTest.java`: real compiled `String[]` comparator
   Bubble target, generic hint and no specialized frame.
6. Run focused tests after each phase, obtain tester/reviewer audit, then
   `mvn -f backend/pom.xml clean package` and commit only R2b files.

Phase 1 precedes detector work. Detector phases are independent after facts;
runtime fixture follows Bubble. Do not change JDI, pom, ObservationResult,
public output schema, API, or UI in this slice.
