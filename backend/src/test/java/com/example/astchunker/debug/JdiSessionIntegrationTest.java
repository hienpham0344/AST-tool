package com.example.astchunker.debug;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.AstVariable;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class JdiSessionIntegrationTest {

  private final AstAnalyzer analyzer = new AstAnalyzer();
  private final TargetCompiler compiler = new TargetCompiler();

  @Test
  void capturesVisibleLocalsFromTheBreakpointEventThread() {
    String source = sampleSource();
    AstAnalyzer.Analysis analysis = analyzer.analyze(source);
    AstVariable expectedValue =
        analysis.variables().stream()
            .filter(variable -> variable.name().equals("value"))
            .findFirst()
            .orElseThrow();

    try (TargetCompiler.CompiledTarget target = compiler.compile(source, analysis)) {
      JdiSession session =
          new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10));

      JdiSession.DebugRun run = session.observe(target, analysis);

      assertThat(run.results())
          .anySatisfy(
              result -> {
                assertThat(result.astNodeId()).isEqualTo(expectedValue.astNodeId());
                assertThat(result.variableName()).isEqualTo("value");
                assertThat(result.declaredType()).isEqualTo("int");
                assertThat(result.runtimeValue()).isEqualTo("2");
              });
      assertThat(run.warnings()).doesNotContain("JDI observation timed out after 10 seconds.");
    }
  }

  @Test
  void formatsArrayContentsFromAnActualJdiTarget() {
    String source = arraySource();
    AstAnalyzer.Analysis analysis = analyzer.analyze(source);

    try (TargetCompiler.CompiledTarget target = compiler.compile(source, analysis)) {
      JdiSession session =
          new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10));

      JdiSession.DebugRun run = session.observe(target, analysis);

      assertThat(run.results())
          .filteredOn(result -> result.variableName().equals("nums"))
          .extracting(result -> result.runtimeValue())
          .contains("[2, 1, 5, 1, 3, 2]");
    }
  }

  @Test
  void mapsVarLocalToItsAstDeclarationUsingTheInferredRuntimeType() {
    String source = varSource();
    AstAnalyzer.Analysis analysis = analyzer.analyze(source);
    AstVariable expected =
        analysis.variables().stream()
            .filter(variable -> variable.name().equals("value"))
            .findFirst()
            .orElseThrow();

    try (TargetCompiler.CompiledTarget target = compiler.compile(source, analysis)) {
      JdiSession.DebugRun run =
          new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
              .observe(target, analysis);

      assertThat(run.results())
          .anySatisfy(
              result -> {
                assertThat(result.astNodeId()).isEqualTo(expected.astNodeId());
                assertThat(result.variableName()).isEqualTo("value");
                assertThat(result.declaredType()).isEqualTo("int");
                assertThat(result.runtimeValue()).isEqualTo("7");
              });
    }
  }

  @Test
  void installsOnlyOneBreakpointForNestedStatementsOnTheSameSourceLine() {
    AstAnalyzer.Analysis analysis = analyzer.analyze(sameLineSource());

    try (TargetCompiler.CompiledTarget target = compiler.compile(sameLineSource(), analysis)) {
      JdiSession session =
          new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10));

      JdiSession.DebugRun run = session.observe(target, analysis);

      assertThat(run.warnings()).anyMatch(warning -> warning.contains("same JDI location"));
      assertThat(run.executions()).hasSizeLessThanOrEqualTo(analysis.observationPoints().size());
    }
  }

  @Test
  void groupsRepeatedHitsOfOneStatementIntoOrderedExecutionObservations() {
    String source = repeatedLineSource();
    AstAnalyzer.Analysis analysis = analyzer.analyze(source);
    com.example.astchunker.model.ObservationPoint printPoint =
        analysis.observationPoints().stream()
            .filter(point -> point.statementKind().equals("ExpressionStmt"))
            .findFirst()
            .orElseThrow();

    try (TargetCompiler.CompiledTarget target = compiler.compile(source, analysis)) {
      JdiSession.DebugRun run =
          new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
              .observe(target, analysis);

      assertThat(run.executions())
          .filteredOn(execution -> execution.statementAstNodeId().equals(printPoint.astNodeId()))
          .extracting(execution -> execution.sequence())
          .isSorted()
          .hasSize(3);
    }
  }

  /** Allows the integration assertion to run without Maven while this environment has no Maven CLI. */
  public static void main(String[] args) {
    new JdiSessionIntegrationTest().capturesVisibleLocalsFromTheBreakpointEventThread();
  }

  private String sampleSource() {
    return """
        package demo;
        public class RuntimeSample {
          public static void main(String[] args) {
            int outer = 1;
            if (args.length == 0) {
              int value = outer + 1;
              System.out.println(value);
            }
          }
        }
        """;
  }

  private String arraySource() {
    return """
        public class ArrayRuntimeSample {
          public static void main(String[] args) {
            int[] nums = {2, 1, 5, 1, 3, 2};
            System.out.println(nums[0]);
          }
        }
        """;
  }

  private String varSource() {
    return """
        public class VarRuntimeSample {
          public static void main(String[] args) {
            var value = 7;
            System.out.println(value);
          }
        }
        """;
  }

  private String sameLineSource() {
    return """
        public class SameLineSample {
          public static void main(String[] args) {
            if (args.length == 0) System.out.println("empty");
          }
        }
        """;
  }

  private String repeatedLineSource() {
    return """
        public class RepeatedLineSample {
          public static void main(String[] args) {
            for (int i = 0; i < 3; i++) {
              System.out.println(i);
            }
          }
        }
        """;
  }
}
