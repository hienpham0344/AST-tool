# R2d draft implementation sequence

Date: 2026-10-17
Status: R2d1 verified; broader R2d remains draft

## Completed first slice (R2d1)

The direct-injection proof passed without a temporary source root. The
smallest production change is in `SortSemanticFacts`: inject a JRE-only
resolver into the original compilation unit and accept short `String` only
after checking the resolved one-argument method's declaring type plus both
operand types. Existing lexical declaration IDs and source ranges remain the
output identity. Unit fixtures cover JDK and shadowed names, overloads,
unresolved types, and all three detectors; a real JVM target covers the short
name. Review found that initial injection overwrote a caller-provided
resolver; a RED regression proved it, and the implementation now preserves
existing resolvers and synchronizes first-time injection. This draft retains
the broader R2d sequence below. Final `mvn -f backend/pom.xml clean package`
passed 293 tests, including the real JVM short-`String[]` target, on
2026-10-17.

R2d2 generalized that exact resolved-call gate to short JDK wrapper names.
Its unit fixtures cover all eight wrappers and a same-file `Integer` shadow;
an `Integer[]` Bubble target runs on a real JVM. The full package passed
298 tests on 2026-10-17. Custom `Comparable` remains outside these slices.

1. Write RED tests for JDK short names, same-file `java` and `String`
   shadowing, overloaded `compareTo`, custom Comparable, unresolved calls,
   and original AST ID stability.
2. Prove the smallest JavaParser Symbol Solver setup against those fixtures.
   Prefer direct injection into the original CompilationUnit if it resolves
   source declarations correctly. Use a temporary source root only if needed.
3. Add a per-compilation-unit resolved-call fact cache that fails closed and
   does not leak mutable solver state across requests.
4. Broaden Bubble, Selection and shift-Insertion natural comparison paths
   from the R2c spelling guard to verified resolved-call facts. Keep
   comparator policy and runtime validation separate.
5. Add real JVM targets with short `String[]` and one same-file custom
   Comparable implementation; assert generic hints and no false sort frames.
6. Run focused tests, independent review, held-out false-positive fixtures,
   then `mvn -f backend/pom.xml clean package` before any commit.

The proof spike is a gate: do not weaken R2c's fail-closed guard until the
resolver tests establish method identity under source shadowing.
