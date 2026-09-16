package com.example.astchunker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.debug.JdiSession;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MainTest {

  @Test
  void inspectsTheDocumentedTargetExampleEndToEnd() throws Exception {
    Path sourceFile = Path.of("examples", "ScopedVariablesSample.java");

    assertThat(sourceFile).exists();
    assertThat(Main.inspect(sourceFile).results())
        .anySatisfy(
            result -> {
              assertThat(result.variableName()).isEqualTo("value");
              assertThat(result.runtimeValue()).isEqualTo("10");
        });
  }

  @Test
  void inspectsSlidingWindowSampleWithFormattedArrayAndRepeatedExecutionSteps() throws Exception {
    Path sourceFile = Path.of("examples", "SlidingWindowSample.java");

    JdiSession.DebugRun run = Main.inspect(sourceFile);

    assertThat(run.results())
        .anySatisfy(
            result -> {
              assertThat(result.variableName()).isEqualTo("nums");
              assertThat(result.runtimeValue()).isEqualTo("[2, 1, 5, 1, 3, 2]");
            });
    assertThat(run.executions()).isNotEmpty();
    assertThat(run.executions()).filteredOn(step -> step.lineNumber() == 18).hasSize(3);
    assertThat(run.executions()).filteredOn(step -> step.lineNumber() == 18)
        .extracting(
            step -> step.variables().stream()
                .filter(variable -> variable.variableName().equals("windowSum"))
                .findFirst()
                .orElseThrow()
                .runtimeValue())
        .containsExactly("6", "6", "4");
  }

  /** Allows the end-to-end assertion to run while Maven is unavailable in this environment. */
  public static void main(String[] args) throws Exception {
    new MainTest().inspectsTheDocumentedTargetExampleEndToEnd();
  }
}
