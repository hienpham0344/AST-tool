package com.example.astchunker.debug;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.ExecutionObservation;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionSemanticsIntegrationTest {
  private JdiSession.DebugRun run(String source) {
    var analysis = new AstAnalyzer().analyze(source);
    try (var target = new TargetCompiler().compile(source, analysis)) {
      return new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(10))
          .observe(target, analysis);
    }
  }

  private ExecutionObservation at(List<ExecutionObservation> steps, int line) {
    return steps.stream().filter(s -> s.lineNumber() == line).findFirst().orElseThrow();
  }

  private String value(ExecutionObservation step, String name) {
    return step.variables().stream()
        .filter(v -> v.variableName().equals(name))
        .map(v -> v.runtimeValue())
        .findFirst()
        .orElse(null);
  }

  @Test
  void capturesBeforeAssignmentAndDoesNotInventTerminalState() {
    var result =
        run(
            """
        public class TimingSample {
          public static void main(String[] args) {
            int x = 1;
            x = 2;
            System.out.println(x);
            x = 3;
          }
        }
        """);
    assertThat(result.warnings()).isEmpty();
    var steps = result.executions();
    assertThat(steps).extracting(ExecutionObservation::lineNumber).containsExactly(3, 4, 5, 6);
    assertThat(value(at(steps, 3), "x")).isNull();
    assertThat(value(at(steps, 4), "x")).isEqualTo("1");
    assertThat(value(at(steps, 5), "x")).isEqualTo("2");
    assertThat(value(at(steps, 6), "x")).isEqualTo("2");
    assertThat(steps).extracting(ExecutionObservation::sequence).containsExactly(1L, 2L, 3L, 4L);
    assertThat(steps)
        .allSatisfy(
            step -> {
              assertThat(step.snapshotPhase()).isEqualTo("BEFORE_LOCATION");
              assertThat(step.granularity()).isEqualTo("LINE_BREAKPOINT");
              assertThat(step.context().className()).isEqualTo("TimingSample");
              assertThat(step.context().methodName()).isEqualTo("main");
              assertThat(step.context().stackDepth()).isEqualTo(1);
              assertThat(step.context().codeIndex()).isNotNegative();
              assertThat(step.variables())
                  .allSatisfy(v -> assertThat(v.lineNumber()).isEqualTo(step.lineNumber()));
            });
  }

  @Test
  void recordsLoopBodyValuesAndVariableLeavingScope() {
    var steps =
        run("""
        public class LoopTimingSample {
          public static void main(String[] args) {
            int total = 0;
            for (int i = 0; i < 3; i++) {
              total += i;
            }
            System.out.println(total);
          }
        }
        """)
            .executions();
    var body = steps.stream().filter(s -> s.lineNumber() == 5).toList();
    assertThat(body).extracting(s -> value(s, "i")).containsExactly("0", "1", "2");
    assertThat(body).extracting(s -> value(s, "total")).containsExactly("0", "0", "1");
    assertThat(body)
        .extracting(ExecutionObservation::statementAstNodeId)
        .containsOnly(body.get(0).statementAstNodeId());
    assertThat(value(at(steps, 7), "total")).isEqualTo("3");
    assertThat(value(at(steps, 7), "i")).isNull();
    assertThat(steps)
        .extracting(s -> s.context().threadId())
        .containsOnly(steps.get(0).context().threadId());
  }

  @Test
  void distinguishesRecursiveFramesAndReturnToCaller() {
    var steps =
        run("""
        public class CallTimingSample {
          static int count(int n) {
            if (n == 0) return 0;
            int result = count(n - 1);
            return result + 1;
          }
          public static void main(String[] args) {
            int answer = count(2);
            System.out.println(answer);
          }
        }
        """)
            .executions();
    var entries = steps.stream().filter(s -> s.lineNumber() == 3).toList();
    assertThat(entries).extracting(s -> value(s, "n")).containsExactly("2", "1", "0");
    assertThat(entries).extracting(s -> s.context().stackDepth()).containsExactly(2, 3, 4);
    assertThat(entries)
        .allSatisfy(
            s -> {
              assertThat(s.context().methodName()).isEqualTo("count");
              assertThat(s.context().methodSignature()).isEqualTo("(I)I");
              assertThat(value(s, "answer")).isNull();
            });
    assertThat(value(at(steps, 8), "answer")).isNull();
    var afterCall = at(steps, 9);
    assertThat(value(afterCall, "answer")).isEqualTo("2");
    assertThat(afterCall.context().methodName()).isEqualTo("main");
    assertThat(afterCall.context().stackDepth()).isEqualTo(1);
    assertThat(steps)
        .filteredOn(s -> s.lineNumber() == 5)
        .extracting(s -> value(s, "result"))
        .containsExactly("0", "1");
  }

  @Test
  void doesNotFabricateStepsForEachStatementOnOneLine() {
    var result =
        run(
            """
        public class SameLineTimingSample {
          public static void main(String[] args) {
            int x = 0;
            x = 1; x = 2;
            System.out.println(x);
          }
        }
        """);
    assertThat(result.warnings()).anyMatch(w -> w.contains("same JDI location"));
    assertThat(result.executions()).filteredOn(s -> s.lineNumber() == 4).hasSize(1);
    assertThat(value(at(result.executions(), 4), "x")).isEqualTo("0");
    assertThat(value(at(result.executions(), 5), "x")).isEqualTo("2");
  }
}
