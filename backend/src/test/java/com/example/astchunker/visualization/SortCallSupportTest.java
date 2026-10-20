package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;

class SortCallSupportTest {
  @Test
  void doesNotTreatFieldNamedLikeClassAsClassQualifiedCall() {
    var source =
        StaticJavaParser.parse(
            """
        class Other { static void quick(int[] data, int low, int high) {} }
        class Sample {
          static Other Sample = new Other();
          static void quick(int[] data, int low, int high) {}
          static void run(int[] data) { Sample.quick(data, 0, data.length - 1); }
        }
        """);
    var owner = source.getClassByName("Sample").orElseThrow();
    var target = owner.getMethodsByName("quick").get(0);
    var call = owner.getMethodsByName("run").get(0).findFirst(MethodCallExpr.class).orElseThrow();

    assertThat(SortCallSupport.sameClassCall(call, target)).isFalse();
  }

  @Test
  void doesNotTreatInheritedInterfaceFieldAsClassQualifiedCall() {
    var source =
        StaticJavaParser.parse(
            """
        class Other { static void quick(int[] data, int low, int high) {} }
        interface I { Other Sample = new Other(); }
        class Sample implements I {
          static void quick(int[] data, int low, int high) {}
          static void run(int[] data) { Sample.quick(data, 0, data.length - 1); }
        }
        """);
    var owner = source.getClassByName("Sample").orElseThrow();
    var target = owner.getMethodsByName("quick").get(0);
    var call = owner.getMethodsByName("run").get(0).findFirst(MethodCallExpr.class).orElseThrow();

    assertThat(SortCallSupport.sameClassCall(call, target)).isFalse();
  }
}
