package com.example.astchunker.algorithm.sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.astchunker.ast.AstAnalyzer;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;

class SortSymbolResolutionProbeTest {
  private final AstAnalyzer parser = new AstAnalyzer();

  @Test void resolvesShortJdkStringOnOriginalAst() {
    var unit = parser.parseCompilationUnit("class S { void sort(String[] values, int i) {"
        + " if (values[i].compareTo(values[i + 1]) > 0) {} } }");
    var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
    String originalId = AstAnalyzer.nodeId(call);
    new JavaSymbolSolver(new ReflectionTypeSolver(true)).inject(unit);

    assertThat(call.resolve().declaringType().getQualifiedName()).isEqualTo("java.lang.String");
    assertThat(call.getScope().orElseThrow().calculateResolvedType().describe()).isEqualTo("java.lang.String");
    assertThat(AstAnalyzer.nodeId(call)).isEqualTo(originalId);
  }

  @Test void resolvesSameFileShadowInsteadOfJdkString() {
    var unit = parser.parseCompilationUnit("""
        class S {
          static class java {
            static class lang {
              static final class String implements Comparable<String> {
                public int compareTo(String other) { return 0; }
              }
            }
          }
          void sort(java.lang.String[] values, int i) {
            if (values[i].compareTo(values[i + 1]) > 0) {}
          }
        }
        """);
    var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
    new JavaSymbolSolver(new ReflectionTypeSolver(true)).inject(unit);

    assertThat(call.resolve().declaringType().getQualifiedName()).isEqualTo("S.java.lang.String");
    assertThat(call.getScope().orElseThrow().calculateResolvedType().describe()).isEqualTo("S.java.lang.String");
  }

  @Test void resolvesSameFileSimpleNameShadowInsteadOfJdkString() {
    var unit = parser.parseCompilationUnit("""
        class S {
          static final class String implements Comparable<String> {
            public int compareTo(String other) { return 0; }
          }
          void sort(String[] values, int i) {
            if (values[i].compareTo(values[i + 1]) > 0) {}
          }
        }
        """);
    var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
    new JavaSymbolSolver(new ReflectionTypeSolver(true)).inject(unit);

    assertThat(call.resolve().declaringType().getQualifiedName()).isEqualTo("S.String");
  }

  @Test void resolvesImportedJdkStringAndSourceComparableOverload() {
    var imported = parser.parseCompilationUnit("import java.lang.String; class S {"
        + " void sort(String[] values, int i) { if (values[i].compareTo(values[i + 1]) > 0) {} } }");
    new JavaSymbolSolver(new ReflectionTypeSolver(true)).inject(imported);
    assertThat(imported.findFirst(MethodCallExpr.class).orElseThrow()
        .resolve().declaringType().getQualifiedName()).isEqualTo("java.lang.String");

    var custom = parser.parseCompilationUnit("""
        class S {
          static final class Item implements Comparable<Item> {
            public int compareTo(Item other) { return 0; }
            public int compareTo(String other) { return 0; }
          }
          void sort(Item[] values, int i) {
            if (values[i].compareTo(values[i + 1]) > 0) {}
          }
        }
        """);
    var call = custom.findFirst(MethodCallExpr.class).orElseThrow();
    new JavaSymbolSolver(new ReflectionTypeSolver(true)).inject(custom);

    var resolved = call.resolve();
    assertThat(resolved.declaringType().getQualifiedName()).isEqualTo("S.Item");
    assertThat(resolved.getNumberOfParams()).isEqualTo(1);
    assertThat(resolved.getParam(0).getType().describe()).isEqualTo("S.Item");
  }

  @Test void unresolvedExternalReceiverDoesNotResolveToJdkMethod() {
    var unit = parser.parseCompilationUnit("class S { void sort(Missing[] values, int i) {"
        + " if (values[i].compareTo(values[i + 1]) > 0) {} } }");
    var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
    new JavaSymbolSolver(new ReflectionTypeSolver(true)).inject(unit);

    assertThatThrownBy(call::resolve).isInstanceOf(RuntimeException.class);
  }
}
