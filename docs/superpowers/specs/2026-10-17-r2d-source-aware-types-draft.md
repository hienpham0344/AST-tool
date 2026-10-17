# R2d draft: source-aware type and call identity

Date: 2026-10-17
Status: R2d1 implemented; broader R2d remains draft

## First verified slice (R2d1)

The original AST accepts direct `JavaSymbolSolver(new ReflectionTypeSolver(true)).inject(unit)`.
Focused fixtures resolve short `String` to `java.lang.String`, same-file nested
`String` and `java.lang.String` shadows to their source declarations, a
source-defined overloaded `compareTo` to its selected overload, and an
unavailable external type to a resolution failure. The call's AST node ID is
unchanged. This proof removes the need for a temporary source root in this
slice, although broader source-defined and cross-file cases remain open.

R2d1 recognizes only a direct short-name `String.compareTo(String)` when
the resolved declaring type, receiver, and argument are all exactly
`java.lang.String`. R2d2 extends the same proof to short names of final JDK
wrapper types (`Byte`, `Short`, `Integer`, `Long`, `Character`, `Float`,
`Double`, `Boolean`). Bubble, Selection, and shift-Insertion remain
static-only for these reference sorts. Shadowed or unresolved calls remain
unknown. General custom `Comparable` and List stay pending.

## Problem

R2b proves only an explicit `java.util.Comparator<E>` parameter. R2c proves
natural `compareTo` only for exact `java.lang.*` spelling inside a very narrow
standalone class. User-written sorting code commonly uses `String[]`, imports,
nested types, and custom `Comparable`. Matching names alone is unsafe: a
compilable nested type named `java` can make `java.lang.String` refer to a
user class. R2c contains a regression test for this case and fails closed.

## Target outcome

Build source-aware, fail-closed resolution for one uploaded Java source file.
For each comparison call, prove the receiver type, argument type, resolved
method declaration and declaring type. Retain original AST node IDs and
source ranges. Accept short `String[]` and source-defined `Comparable` only
when the resolved method and ordering contract are supported; unresolved,
ambiguous, or conflicting symbols stay unknown. Keep static recognition and
runtime validation separate.

No new Maven dependency is needed: `javaparser-symbol-solver-core` is already
present. No JDI, API/DTO, ObservationResult, or UI change belongs in this
slice. List mutation and new sort families follow in separate slices.

## Proof requirements before implementation

1. Test whether `JavaSymbolSolver.inject(CompilationUnit)` with the existing
   in-memory AST resolves both JDK `String.compareTo` and a same-file shadowed
   `java.lang.String` correctly. `ReflectionTypeSolver` alone must not be
   assumed source-aware.
2. If same-file types need `JavaParserTypeSolver`, provide it a temporary
   single-file source root, resolve eagerly, cache immutable method/type facts,
   and clean the root. Preserve original AST IDs; do not reparse just to return
   replacement nodes to callers.
3. Verify concurrency, cleanup on parse/resolve failure, package paths,
   overloaded methods, inheritance, generic substitution, and unavailable
   external dependencies. All failures must return unknown without crashing.
4. Hold out handwritten shadowing and overload fixtures to measure false
   positives, not only canonical renamed templates.

## Scope boundary

The target remains one self-contained Java 17 source file. A resolved
`Arrays.sort`/`List.sort` call will later receive a distinct library-sort
label rather than a guessed internal algorithm. Runtime collection of
arbitrary custom Lists requires a separate JDI design and approval.
