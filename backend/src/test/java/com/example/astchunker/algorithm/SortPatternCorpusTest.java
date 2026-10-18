package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.BreakpointMapper;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SortPattern.Direction;
import com.example.astchunker.visualization.VisualTraceBuilder;
import com.github.javaparser.ast.Node;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Source variants shared by detector and runtime work. Expectations are hand-reviewed literals. */
class SortPatternCorpusTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private final AlgorithmPatternAnalyzer analyzer = new AlgorithmPatternAnalyzer();

  static Stream<Arguments> recognized() {
    return Stream.of(
        Arguments.of("bubble-for.java", "bubble-sort", Direction.ASCENDING),
        Arguments.of("bubble-while.java", "bubble-sort", Direction.ASCENDING),
        Arguments.of("bubble-rewritten-bound.java", "bubble-sort", Direction.ASCENDING),
        Arguments.of("bubble-helper-swap.java", "bubble-sort", Direction.ASCENDING),
        Arguments.of("bubble-early-exit.java", "bubble-sort", Direction.ASCENDING),
        Arguments.of("bubble-while-early-exit.java", "bubble-sort", Direction.ASCENDING),
        Arguments.of("bubble-descending.java", "bubble-sort", Direction.DESCENDING),
        Arguments.of("selection-for.java", "selection-sort", Direction.ASCENDING),
        Arguments.of("selection-while.java", "selection-sort", Direction.ASCENDING),
        Arguments.of("selection-helper-swap.java", "selection-sort", Direction.ASCENDING),
        Arguments.of("selection-rewritten-bound.java", "selection-sort", Direction.ASCENDING),
        Arguments.of("selection-descending.java", "selection-sort", Direction.DESCENDING),
        Arguments.of("insertion-for.java", "insertion-sort", Direction.ASCENDING),
        Arguments.of("insertion-while.java", "insertion-sort", Direction.ASCENDING),
        Arguments.of("insertion-reordered-locals.java", "insertion-sort", Direction.ASCENDING),
        Arguments.of("insertion-rewritten-bound.java", "insertion-sort", Direction.ASCENDING),
        Arguments.of("insertion-descending.java", "insertion-sort", Direction.DESCENDING));
  }

  static Stream<String> rejected() {
    return Stream.of(
        "bubble-incomplete-swap.java",
        "bubble-early-exit-missing-flag.java",
        "bubble-short-scan.java",
        "bubble-overridable-helper.java",
        "selection-wrong-index.java",
        "insertion-wrong-shift.java",
        "insertion-short-pass.java");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recognized")
  void recognizesEstablishedSortPatterns(String file, String type, Direction direction)
      throws Exception {
    var unit = parser.parseCompilationUnit(source(file));
    List<AlgorithmHint> hints = analyzer.analyze(unit);
    Map<String, Node> nodes = unit.findAll(Node.class).stream()
        .collect(Collectors.toMap(AstAnalyzer::nodeId, node -> node));
    Set<String> sourceNodeIds = nodes.keySet();
    assertThat(hints)
        .singleElement()
        .satisfies(
            hint -> {
              assertThat(hint.type()).isEqualTo(type);
              assertThat(hint.sort().direction()).isEqualTo(direction);
              assertThat(hint.sort().comparisonAstNodeId()).isNotBlank();
              assertThat(sourceNodeIds).contains(hint.sort().comparisonAstNodeId());
              assertThat(sourceNodeIds).containsAll(hint.sort().swapStatementAstNodeIds());
              assertThat(sourceNodeIds).containsAll(hint.sort().operationAstNodeIds().values());
              assertThat(nodes.get(hint.sort().comparisonAstNodeId()).toString())
                  .isEqualTo(expectedComparison(file));
              assertThat(hint.sort().swapStatementAstNodeIds().stream()
                  .map(id -> nodes.get(id).toString().trim()).toList())
                  .containsExactlyElementsOf(expectedSwapStatements(file));
              if (type.equals("insertion-sort")) {
                assertThat(nodes.get(hint.sort().operationAstNodeIds().get("shiftWrite")).toString().trim())
                    .isEqualTo("data[scan + 1] = data[scan];");
                assertThat(nodes.get(hint.sort().operationAstNodeIds().get("insertWrite")).toString().trim())
                    .isEqualTo("data[scan + 1] = key;");
              }
            });
  }

  @ParameterizedTest(name = "paired mutation of {0}")
  @MethodSource("recognized")
  void rejectsAWrongWritePairedWithEveryPositive(String file, String type, Direction direction)
      throws Exception {
    String original = source(file);
    String altered = wrongWrite(file, original);
    assertThat(altered).isNotEqualTo(original);
    assertThat(analyzer.analyze(parser.parseCompilationUnit(altered)))
        .noneMatch(hint -> hint.type().endsWith("-sort"));
  }

  @ParameterizedTest(name = "runtime trace of {0}")
  @MethodSource("recognized")
  void tracesEveryPositiveFromObservedArrayValues(String file, String type, Direction direction)
      throws Exception {
    String input = direction == Direction.ASCENDING ? "2, 1" : "1, 2";
    String runnable = source(file)
        .replace("class Sample {", "public class SortSample {\n"
            + "  public static void main(String[] args) { new SortSample().sort(new int[] {"
            + input + "}); }")
        .replaceFirst("(?m)^  }$", "    System.out.println(java.util.Arrays.toString(data));\n  }");
    var analysis = parser.analyze(runnable);
    var hints = analyzer.analyze(analysis.compilationUnit());
    assertThat(hints).hasSize(1);
    try (var target = new TargetCompiler().compile(runnable, analysis)) {
      var observed = new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
          .observe(target, analysis);
      var steps = new VisualTraceBuilder().build(analysis, hints, observed.executions());
      var frames = steps.stream().flatMap(step -> step.visualStates().stream())
          .map(state -> state.sortFrame()).filter(Objects::nonNull).toList();
      assertThat(frames).anySatisfy(frame -> {
        assertThat(frame.comparison()).isNotNull();
        assertThat(frame.comparison().status()).isEqualTo("AVAILABLE_FROM_SNAPSHOT");
      });
      assertThat(frames).filteredOn(frame -> frame.pendingMutation() != null)
          .anySatisfy(frame -> assertThat(frame.pendingMutation().status())
              .isEqualTo("PENDING_BEFORE_LOCATION"));
      assertThat(frames).filteredOn(frame -> frame.completedMutation() != null)
          .anySatisfy(frame -> assertThat(frame.completedMutation().status())
              .isEqualTo("OBSERVED_AFTER_WRITE"));
      var ending = steps.stream().filter(step -> step.code().startsWith("System.out.println"))
          .findFirst().orElseThrow();
      assertThat(ending.visualStates()).hasSize(1);
      assertThat(ending.visualStates().get(0).algorithm()).isEqualTo(type);
      assertThat(ending.visualStates().get(0).array().values())
          .containsExactlyElementsOf(direction == Direction.ASCENDING ? List.of(1, 2) : List.of(2, 1));
      assertThat(ending.visualStates().get(0).sortFrame().sortedRegion().status())
          .isEqualTo("VERIFIED_SORTED");
    }
  }

  private static String expectedComparison(String file) {
    if (file.startsWith("bubble")) {
      if (file.equals("bubble-rewritten-bound.java")) return "data[scan + 1] < data[scan]";
      return "data[scan] " + (file.contains("descending") ? "<" : ">") + " data[scan + 1]";
    }
    if (file.startsWith("selection"))
      return "data[scan] " + (file.contains("descending") ? ">" : "<") + " data[selected]";
    return "data[scan] " + (file.contains("descending") ? "<" : ">") + " key";
  }

  private static List<String> expectedSwapStatements(String file) {
    if (file.startsWith("insertion")) return List.of();
    if (file.contains("helper"))
      return List.of("int saved = values[left];", "values[left] = values[right];", "values[right] = saved;");
    String left = file.startsWith("bubble") ? "scan" : "pass";
    String right = file.startsWith("bubble") ? "scan + 1" : "selected";
    String temp = file.equals("bubble-for.java")
        || file.equals("selection-for.java")
        || file.equals("selection-rewritten-bound.java") ? "temp" : "saved";
    return List.of(
        "int " + temp + " = data[" + left + "];",
        "data[" + left + "] = data[" + right + "];",
        "data[" + right + "] = " + temp + ";");
  }

  private static String wrongWrite(String file, String source) {
    if (file.startsWith("insertion"))
      return source.replace("data[scan + 1] = key;", "data[scan + 1] = 0;");
    if (file.contains("helper"))
      return source.replace("values[right] = saved;", "values[right] = 0;");
    String lastWrite = expectedSwapStatements(file).get(2);
    return source.replace(lastWrite, lastWrite.replaceAll("= [a-z]+;", "= 0;"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejected")
  void rejectsNearMisses(String file) throws Exception {
    assertThat(detect(file)).noneMatch(hint -> hint.type().endsWith("-sort"));
  }

  private List<AlgorithmHint> detect(String file) throws Exception {
    return analyzer.analyze(parser.parseCompilationUnit(source(file)));
  }

  private String source(String file) throws Exception {
    String path = "sort-patterns/" + file;
    try (InputStream input = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path), path)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
