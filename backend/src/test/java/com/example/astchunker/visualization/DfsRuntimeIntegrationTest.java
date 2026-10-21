package com.example.astchunker.visualization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.*;
import com.example.astchunker.mapping.*;
import com.example.astchunker.model.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class DfsRuntimeIntegrationTest {
  @Test
  void matrixRecursionUsesRealInvocationsAndSharedGraph() throws Exception {
    verify("DfsMatrixSample.java", "ADJACENCY_MATRIX");
  }

  @Test
  void listRecursionUsesBoundedFieldOnlyGraphProjection() throws Exception {
    verify("DfsListSample.java", "ADJACENCY_LIST");
  }

  private void verify(String fixture, String representation) throws Exception {
    String source = Files.readString(Path.of("examples/graph", fixture));
    var analysis = new AstAnalyzer().analyze(source);
    var hints = new AlgorithmPatternAnalyzer().analyze(analysis.compilationUnit());
    var dfs = hints.stream().filter(h -> h.dfs() != null).toList();
    assertThat(dfs).hasSize(1);
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
      assertThat(steps).isNotEmpty();
      assertThat(steps.get(0).context().methodName()).isEqualTo("main");
      assertThat(steps.stream().filter(s -> s.context().methodName().equals("main")))
          .allSatisfy(s -> assertThat(s.visualStates()).noneMatch(v -> v.dfsFrame() != null));
      var states =
          steps.stream()
              .flatMap(s -> s.visualStates().stream())
              .filter(s -> s.dfsFrame() != null)
              .toList();
      assertThat(states).isNotEmpty().allSatisfy(s -> assertThat(s.status()).isEqualTo("READY"));
      var frames = states.stream().map(VisualState::dfsFrame).toList();
      assertThat(frames.stream().map(DfsFrame::invocationId).distinct()).hasSize(4);
      assertThat(frames.stream().map(DfsFrame::graphObjectId).distinct()).hasSize(1);
      assertThat(frames.stream().map(DfsFrame::visitedObjectId).distinct()).hasSize(1);
      assertThat(frames.stream().map(DfsFrame::stackDepth).distinct()).hasSize(4);
      assertThat(frames)
          .allSatisfy(
              f -> {
                assertThat(f.graphObjectId()).isNotNull();
                assertThat(f.visitedObjectId()).isNotNull();
                assertThat(f.representation()).isEqualTo(representation);
                assertThat(f.graphStatus()).isEqualTo("AVAILABLE");
                assertThat(f.visitedStatus()).isEqualTo("AVAILABLE");
                assertThat(f.graph().get("vertexCount")).isEqualTo(5);
                assertThat(f.visitedVertices()).doesNotContain(4);
                assertThat(f.snapshotPhase()).isEqualTo("BEFORE_LOCATION");
              });
      assertThat(frames)
          .anySatisfy(f -> assertThat(f.visitedVertices()).containsExactly(0, 1, 2, 3));
      assertThat(frames)
          .anySatisfy(
              f -> {
                assertThat(f.markStatus()).isEqualTo("PENDING");
                assertThat(f.visitedVertices()).doesNotContain(f.currentVertex());
              });
      assertThat(frames)
          .anySatisfy(
              f -> {
                assertThat(f.callStatus()).isEqualTo("PENDING");
                assertThat(f.edgeStatus()).isEqualTo("CANDIDATE");
                assertThat(f.examiningEdge()).containsEntry("from", 0).containsEntry("to", 1);
              });
      var initialByInvocation = new LinkedHashMap<Long, DfsFrame>();
      for (var f : frames) initialByInvocation.putIfAbsent(f.invocationId(), f);
      assertThat(initialByInvocation.values())
          .extracting(DfsFrame::currentVertex)
          .containsExactly(0, 1, 2, 3);
      for (var step : steps)
        for (var state : step.visualStates())
          if (state.dfsFrame() != null) {
            assertThat(state.dfsFrame().invocationId()).isEqualTo(step.context().invocationId());
            assertThat(state.dfsFrame().stackDepth()).isEqualTo(step.context().stackDepth());
          }
      assertThat(steps)
          .anySatisfy(
              s -> assertThat(s.variables()).anyMatch(v -> v.variableName().equals("local")));
      assertThat(steps)
          .anySatisfy(
              s -> {
                assertThat(s.context().methodName()).isEqualTo("explore");
                assertThat(s.code()).contains("int finished = vertex");
                // This final declaration is observed BEFORE it executes; no later source line
                // in this scope promises a snapshot of the newly initialized local.
                assertThat(s.variables()).noneMatch(v -> v.variableName().equals("finished"));
              });
    }
  }
}
