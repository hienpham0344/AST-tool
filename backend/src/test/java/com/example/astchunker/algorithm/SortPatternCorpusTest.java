package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SortPattern.Direction;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
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
        Arguments.of("selection-for.java", "selection-sort", Direction.ASCENDING),
        Arguments.of("selection-while.java", "selection-sort", Direction.ASCENDING),
        Arguments.of("selection-helper-swap.java", "selection-sort", Direction.ASCENDING),
        Arguments.of("insertion-for.java", "insertion-sort", Direction.ASCENDING),
        Arguments.of("insertion-while.java", "insertion-sort", Direction.ASCENDING));
  }

  static Stream<String> rejected() {
    return Stream.of("bubble-incomplete-swap.java", "bubble-early-exit-missing-flag.java", "selection-wrong-index.java", "insertion-wrong-shift.java");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recognized")
  void recognizesEstablishedSortPatterns(String file, String type, Direction direction)
      throws Exception {
    List<AlgorithmHint> hints = detect(file);
    assertThat(hints)
        .singleElement()
        .satisfies(
            hint -> {
              assertThat(hint.type()).isEqualTo(type);
              assertThat(hint.sort().direction()).isEqualTo(direction);
              assertThat(hint.sort().comparisonAstNodeId()).isNotBlank();
              assertThat(hint.sort().operationAstNodeIds()).isNotNull();
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejected")
  void rejectsNearMisses(String file) throws Exception {
    assertThat(detect(file)).noneMatch(hint -> hint.type().endsWith("-sort"));
  }

  private List<AlgorithmHint> detect(String file) throws Exception {
    String path = "sort-patterns/" + file;
    try (InputStream input = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path), path)) {
      String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      return analyzer.analyze(parser.parseCompilationUnit(source));
    }
  }
}
