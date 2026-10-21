package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import java.util.List;
import org.junit.jupiter.api.Test;

class DfsDetectorTest {
  private String matrix() {
    return """
    class Sample {
      static void wander(int[][] links, boolean[] done, int vertex) {
        done[vertex] = true;
        for (int candidate = 0; candidate < links.length; candidate++) {
          if (links[vertex][candidate] != 0 && !done[candidate]) {
            wander(links, done, candidate);
          }
        }
      }
    }
    """;
  }

  private List<AlgorithmHint> dfs(String source) {
    return new AlgorithmPatternAnalyzer()
        .analyze(new AstAnalyzer().parseCompilationUnit(source)).stream()
            .filter(hint -> hint.type().equals("dfs"))
            .toList();
  }

  @Test
  void analyzerRecognizesRenamedRecursiveMatrixMethodWithoutMainSeed() {
    assertThat(dfs(matrix())).hasSize(1);
  }

  private String adjacencyList() {
    return "import java.util.List;\n"
        + matrix()
            .replace("int[][] links", "List<List<Integer>> links")
            .replace(
                "for (int candidate = 0; candidate < links.length; candidate++)",
                "for (int candidate : links.get(vertex))")
            .replace("links[vertex][candidate] != 0 && ", "");
  }

  @Test
  void bindsExactMethodAndAllOperationAnchors() {
    var unit = new AstAnalyzer().parseCompilationUnit(matrix());
    var hints = new AlgorithmPatternAnalyzer().analyze(unit);
    assertThat(hints).hasSize(1);
    var hint = hints.get(0);
    var method =
        unit.findFirst(com.github.javaparser.ast.body.MethodDeclaration.class).orElseThrow();
    assertThat(hint.methodAstNodeId()).isEqualTo(AstAnalyzer.nodeId(method));
    assertThat(hint.patternAstNodeId()).isEqualTo(hint.methodAstNodeId());
    assertThat(hint.dfs().schemaVersion()).isEqualTo(1);
    assertThat(hint.dfs().representation()).isEqualTo("ADJACENCY_MATRIX");
    assertThat(hint.dfs().declarationIds())
        .isEqualTo(hint.variableDeclarationIds())
        .containsOnlyKeys("graph", "visited", "current", "neighbor");
    assertThat(hint.dfs().operationAstNodeIds())
        .containsOnlyKeys("mark", "neighborLoop", "guard", "recursiveCall");
    for (String id : hint.dfs().operationAstNodeIds().values()) {
      assertThat(
              unit.findAll(com.github.javaparser.ast.Node.class).stream()
                  .anyMatch(node -> AstAnalyzer.nodeId(node).equals(id)))
          .isTrue();
    }
    assertThat(hint.dfs().evidence()).isNotEmpty();
  }

  @Test
  void recognizesActualJdkNestedListsAndQualifiedNames() {
    assertThat(dfs(adjacencyList())).hasSize(1);
    assertThat(dfs(adjacencyList()).get(0).dfs().representation()).isEqualTo("ADJACENCY_LIST");
    assertThat(
            dfs(
                adjacencyList()
                    .replace(
                        "List<List<Integer>>",
                        "java.util.List<java.util.List<java.lang.Integer>>")))
        .hasSize(1);
    assertThat(dfs(adjacencyList().replace("import java.util.List;", "import java.util.*;")))
        .hasSize(1);
  }

  @Test
  void acceptsReorderedParametersEntryGuardAndOrdinaryScalarLocals() {
    String source =
        matrix()
            .replace("boolean[] done, int vertex", "int vertex, boolean[] done")
            .replace("wander(links, done, candidate)", "wander(links, candidate, done)")
            .replace(
                "done[vertex] = true;",
                "if (done[vertex]) return; int local = vertex + 10; done[vertex] = true;")
            .replace("    }\n  }\n", "    } int finished = vertex;\n  }\n");
    assertThat(dfs(source)).hasSize(1);
    assertThat(dfs(matrix().replace("static void", "private void"))).hasSize(1);
    assertThat(dfs(matrix().replace("static void", "final void"))).hasSize(1);
    assertThat(
            dfs(
                matrix()
                    .replace("class Sample", "final class Sample")
                    .replace("static void", "void")))
        .hasSize(1);
  }

  @Test
  void acceptsEquivalentMatrixGuardOrderingAndParentheses() {
    assertThat(
            dfs(
                matrix()
                    .replace(
                        "links[vertex][candidate] != 0 && !done[candidate]",
                        "!(done[candidate]) && 0 != (links[vertex][candidate])")))
        .hasSize(1);
    assertThat(dfs(matrix().replace("candidate++", "++candidate"))).hasSize(1);
  }

  @Test
  void rejectsWrongMarkGuardIndicesAndRecursionArguments() {
    for (String source :
        List.of(
            matrix().replace("done[vertex] = true", "done[candidate] = true"),
            matrix().replace("done[vertex] = true", "done[vertex] = false"),
            matrix().replace("done[vertex] = true", "done[vertex] |= true"),
            matrix().replace("done[vertex] = true;", ""),
            matrix().replace("done[vertex] = true;", "if (vertex > 0) done[vertex] = true;"),
            matrix().replace("!done[candidate]", "done[candidate]"),
            matrix().replace("!done[candidate]", "!done[vertex]"),
            matrix().replace(" && ", " || "),
            matrix().replace("links[vertex][candidate]", "links[candidate][vertex]"),
            matrix().replace("!= 0", "== 0"),
            matrix().replace("wander(links, done, candidate)", "wander(links, done, vertex)"),
            matrix().replace("wander(links, done, candidate)", "wander(other, done, candidate)"),
            matrix().replace("wander(links, done, candidate)", "wander(links, other, candidate)"),
            matrix()
                .replace("wander(links, done, candidate)", "wander(links, done, candidate + 1)"),
            matrix().replace("candidate < links.length", "candidate <= links.length"),
            matrix().replace("candidate = 0", "candidate = 1"),
            matrix().replace("candidate++", "candidate--"))) {
      assertThat(dfs(source)).as(source).isEmpty();
    }
  }

  @Test
  void rejectsAliasingMutationEscapeExtraWritesAndControlFlow() {
    for (String injected :
        List.of(
            "int[] alias = links[vertex];",
            "int[][] alias = links;",
            "boolean[] alias = done;",
            "links[vertex][0] = 1;",
            "done[0] = true;",
            "done = other;",
            "vertex++;",
            "change(links);",
            "change(done);",
            "change(vertex);",
            "return;",
            "Runnable later = () -> wander(links, done, vertex);")) {
      assertThat(dfs(matrix().replace("done[vertex] = true;", injected + "done[vertex] = true;")))
          .as(injected)
          .isEmpty();
    }
    for (String replacement :
        List.of(
            "if (vertex > 0) wander(links, done, candidate);",
            "Runnable later = () -> wander(links, done, candidate);",
            "new Object() { void run() { wander(links, done, candidate); } };",
            "break; wander(links, done, candidate);",
            "continue; wander(links, done, candidate);",
            "done[candidate] = true; wander(links, done, candidate);",
            "wander(links, done, candidate); done[candidate] = false;")) {
      assertThat(dfs(matrix().replace("wander(links, done, candidate);", replacement)))
          .as(replacement)
          .isEmpty();
    }
    assertThat(
            dfs(
                matrix()
                    .replace("if (links[vertex]", "if (links[vertex]")
                    .replace("      }\n    }", "      } else { return; }\n    }")))
        .isEmpty();
  }

  @Test
  void rejectsAmbiguousOverloadsReceiversAndOverridableDispatch() {
    assertThat(dfs(matrix().replace("static void wander", "void wander"))).isEmpty();
    assertThat(dfs(matrix().replace("class Sample", "class Sample extends Parent"))).isEmpty();
    assertThat(
            dfs(
                matrix()
                    .replace(
                        "  static void wander",
                        "  static void wander(Object a, Object b, int c) {}\n"
                            + "  static void wander")))
        .isEmpty();
    for (String receiver : List.of("this.", "other.", "Sample.")) {
      assertThat(
              dfs(
                  matrix()
                      .replace(
                          "wander(links, done, candidate)",
                          receiver + "wander(links, done, candidate)")))
          .as(receiver)
          .isEmpty();
    }
  }

  @Test
  void rejectsCustomUnresolvedAndRawListSymbols() {
    for (String source :
        List.of(
            adjacencyList().replace("import java.util.List;", "import custom.List;"),
            adjacencyList().replace("import java.util.List;", ""),
            adjacencyList().replace("class Sample", "class List<T> {} class Sample"),
            adjacencyList().replace("class Sample", "class Integer {} class Sample"),
            adjacencyList().replace("class Sample", "class Sample<List>"),
            adjacencyList().replace("class Sample", "class Sample<Integer>"),
            adjacencyList().replace("List<List<Integer>>", "List"),
            adjacencyList().replace("List<List<Integer>>", "List<List<Long>>"),
            adjacencyList().replace("List<List<Integer>>", "List<List<? extends Integer>>"),
            adjacencyList()
                .replace("import java.util.List;", "import java.util.*; import custom.*;"),
            adjacencyList()
                .replace("import java.util.List;", "import java.util.List; import custom.Integer;"),
            adjacencyList().replace("links.get(vertex)", "links.get(candidate)"),
            adjacencyList().replace("!done[candidate]", "!done[vertex]"),
            adjacencyList()
                .replace("done[vertex] = true;", "links.clear(); done[vertex] = true;"))) {
      assertThat(dfs(source)).as(source).isEmpty();
    }
  }

  @Test
  void rejectsUnsafeEntryGuardsAndShadowedRoles() {
    for (String prefix :
        List.of(
            "if (!done[vertex]) return;",
            "if (done[0]) return;",
            "if (done[vertex]) { change(done); return; }",
            "if (done[vertex]) return; else return;",
            "boolean[] done = other;",
            "int vertex = 0;")) {
      assertThat(dfs(matrix().replace("done[vertex] = true;", prefix + "done[vertex] = true;")))
          .as(prefix)
          .isEmpty();
    }
    assertThat(
            dfs(
                matrix()
                    .replace(
                        "if (links[vertex][candidate]",
                        "int candidate = vertex; if (links[vertex][candidate]")))
        .isEmpty();
  }

  @Test
  void doesNotSuppressAnotherCallableOnTheSameSourceLine() {
    String search =
        "static void search(int[] data,int target) { int lo=0,hi=data.length-1; "
            + "while(lo<=hi) { int pivot=(lo+hi)/2; if(data[pivot]==target)return; "
            + "if(data[pivot]<target)lo=pivot+1;else hi=pivot-1; }}";
    String source = matrix().replace("\n", " ").replaceFirst("} $", search + "}");
    assertThat(
            new AlgorithmPatternAnalyzer().analyze(new AstAnalyzer().parseCompilationUnit(source)))
        .extracting(AlgorithmHint::type)
        .containsExactlyInAnyOrder("dfs", "binary-search");
  }

  @Test
  void rejectsTypesShadowingQualifiedJdkPackages() {
    String source =
        adjacencyList()
                .replace("List<List<Integer>>", "java.util.List<java.util.List<java.lang.Integer>>")
            + "class java { static class util { static class List<T> {} } "
            + "static class lang { static class Integer {} } }";
    assertThat(dfs(source)).isEmpty();
  }
}
