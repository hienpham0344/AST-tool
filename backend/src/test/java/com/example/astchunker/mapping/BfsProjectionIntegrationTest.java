package com.example.astchunker.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.astchunker.model.*;
import com.sun.jdi.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BfsProjectionIntegrationTest {
  @Test
  void selectsOnlyRuntimeAggregateRolesAndOmitsConflicts() {
    var hint =
        hint(
            "ADJACENCY_MATRIX",
            Map.of(
                "graph",
                "g",
                "queue",
                "q",
                "visited",
                "v",
                "source",
                "s",
                "current",
                "c",
                "neighbor",
                "n"));
    var projection = BfsProjection.fromHints(List.of(hint));
    assertThat(projection.roles()).containsOnlyKeys("g", "q", "v");
    assertThat(projection.fieldsFor("g"))
        .isEqualTo(new BfsProjection.Fields("graph", "ADJACENCY_MATRIX"));
    var conflict = hint("ADJACENCY_LIST", Map.of("graph", "g", "queue", "q", "visited", "v"));
    assertThat(BfsProjection.fromHints(List.of(hint, conflict)).roles()).containsOnlyKeys("q", "v");
  }

  @Test
  void mapperProjectsBeforeGenericFormattingAndRetainsArrayIdentity() throws Exception {
    var frame = mock(StackFrame.class);
    var local = mock(LocalVariable.class);
    when(local.name()).thenReturn("flags");
    when(local.typeName()).thenReturn("boolean[]");
    when(frame.visibleVariables()).thenReturn(List.of(local));
    var array = mock(ArrayReference.class);
    var type = mock(ReferenceType.class);
    when(type.name()).thenReturn("boolean[]");
    when(array.referenceType()).thenReturn(type);
    when(array.uniqueID()).thenReturn(27L);
    when(array.length()).thenReturn(1);
    var flag = mock(BooleanValue.class);
    when(flag.value()).thenReturn(true);
    when(array.getValue(0)).thenReturn(flag);
    when(frame.getValue(local)).thenReturn(array);
    var point = new ObservationPoint("p", "Sample", 3, 3, 8, 1, 10, "ExpressionStmt", "call();");
    var declaration = new AstVariable("v", "flags", "boolean[]", 2, 2, 1, 10);
    var values =
        new VariableMapper()
            .map(
                frame,
                point,
                List.of(declaration),
                3,
                new IntervalValueProjection(Map.of()),
                new IntegerListProjection(Set.of()),
                new BfsProjection(Map.of("v", new BfsProjection.Fields("visited", ""))));
    assertThat(values)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.visualType()).isEqualTo("bfs-visited");
              assertThat(value.visualValue())
                  .isEqualTo(
                      Map.of(
                          "values",
                          List.of(true),
                          "length",
                          1,
                          "status",
                          "AVAILABLE",
                          "truncated",
                          false));
              assertThat(value.objectId()).isEqualTo(27L);
              assertThat(value.arrayObjectId()).isEqualTo(27L);
            });
    verify(array, times(1)).getValue(0);
    verify(array, never()).getValues();
  }

  @Test
  void nullProjectedQueueIsUnknownWhileHealthyScalarSurvives() throws Exception {
    var frame = mock(StackFrame.class);
    var q = mock(LocalVariable.class);
    var scalar = mock(LocalVariable.class);
    when(q.name()).thenReturn("q");
    when(q.typeName()).thenReturn("java.util.Queue");
    when(scalar.name()).thenReturn("current");
    when(scalar.typeName()).thenReturn("int");
    when(frame.visibleVariables()).thenReturn(List.of(q, scalar));
    var value = mock(IntegerValue.class);
    when(value.toString()).thenReturn("4");
    when(frame.getValue(scalar)).thenReturn(value);
    var point = new ObservationPoint("p", "Sample", 3, 3, 8, 1, 10, "ExpressionStmt", "call();");
    var declaration = new AstVariable("q-id", "q", "Queue<Integer>", 2, 2, 1, 10);
    var values =
        new VariableMapper()
            .map(
                frame,
                point,
                List.of(declaration),
                3,
                new IntervalValueProjection(Map.of()),
                new IntegerListProjection(Set.of()),
                new BfsProjection(Map.of("q-id", new BfsProjection.Fields("queue", ""))));
    assertThat(values.get(0).visualType()).isEqualTo("bfs-queue");
    assertThat(((Map<?, ?>) values.get(0).visualValue()).get("status")).isEqualTo("UNKNOWN");
    assertThat(values.get(1).runtimeValue()).isEqualTo("4");
    when(frame.getValue(q)).thenThrow(new RuntimeException("local inaccessible"));
    var unavailable =
        new VariableMapper()
            .map(
                frame,
                point,
                List.of(declaration),
                3,
                new IntervalValueProjection(Map.of()),
                new IntegerListProjection(Set.of()),
                new BfsProjection(Map.of("q-id", new BfsProjection.Fields("queue", ""))));
    assertThat(unavailable.get(0).visualType()).isEqualTo("bfs-queue");
    assertThat(((Map<?, ?>) unavailable.get(0).visualValue()).get("status")).isEqualTo("UNKNOWN");
    assertThat(unavailable.get(1).runtimeValue()).isEqualTo("4");
  }

  private static AlgorithmHint hint(String representation, Map<String, String> declarations) {
    var hint = mock(AlgorithmHint.class);
    when(hint.bfs())
        .thenReturn(new BfsPattern(1, representation, declarations, Map.of(), List.of(), 0));
    return hint;
  }
}
