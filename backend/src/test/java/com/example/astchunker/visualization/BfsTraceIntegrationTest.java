package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.BreakpointMapper;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.*;
import com.example.astchunker.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class BfsTraceIntegrationTest {
  @Test
  void matrixQueueAndVisitedMatchActualBfsExecution() throws Exception {
    verify("BfsMatrixSample.java", "ADJACENCY_MATRIX");
  }

  @Test
  void listWithDuplicateEdgesUsesFifoWithoutRevisiting() throws Exception {
    verify("BfsListSample.java", "ADJACENCY_LIST");
  }

  private void verify(String fixture, String representation) throws Exception {
    String source = Files.readString(Path.of("examples/graph", fixture));
    var analysis = new AstAnalyzer().analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    assertThat(hints)
        .singleElement()
        .satisfies(
            hint -> {
              assertThat(hint.type()).isEqualTo("bfs");
              assertThat(hint.bfs().representation()).isEqualTo(representation);
            });
    try (var target = new TargetCompiler().compile(source, analysis)) {
      var run =
          new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofSeconds(15))
              .observe(
                  target,
                  analysis,
                  IntervalValueProjection.fromHints(hints),
                  IntegerListProjection.fromHints(hints),
                  BfsProjection.fromHints(hints));
      assertThat(run.warnings()).isEmpty();
      var steps = new VisualTraceBuilder().build(analysis, hints, run.executions());
      var states =
          steps.stream()
              .flatMap(s -> s.visualStates().stream())
              .filter(s -> s.bfsFrame() != null)
              .toList();
      assertThat(states).isNotEmpty();
      assertThat(states).allSatisfy(state -> assertThat(state.status()).isEqualTo("READY"));
      var frames = states.stream().map(VisualState::bfsFrame).toList();
      assertThat(frames).anySatisfy(frame -> assertThat(frame.queue()).containsExactly(1, 2));
      assertThat(frames)
          .anySatisfy(frame -> assertThat(frame.visitedVertices()).containsExactly(0, 1, 2, 3));
      assertThat(frames)
          .allSatisfy(
              frame -> {
                assertThat(frame.visitedVertices()).doesNotContain(4);
                assertThat(frame.snapshotPhase()).isEqualTo("BEFORE_LOCATION");
                assertThat(frame.graph().get("vertexCount")).isEqualTo(5);
                assertThat(frame.queue()).doesNotHaveDuplicates();
              });
      var events =
          steps.stream()
              .flatMap(s -> s.visualEvents().stream())
              .filter(e -> List.of("ENQUEUE", "DEQUEUE", "VISIT").contains(e.type()))
              .toList();
      assertThat(events).extracting(VisualEvent::type).contains("ENQUEUE", "DEQUEUE", "VISIT");
      assertThat(events).allSatisfy(e -> assertThat(e.fromSequence()).isLessThan(e.toSequence()));
    }
  }
}
