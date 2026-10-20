package com.example.astchunker.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sun.jdi.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BfsValueProjectorTest {
  private final BfsValueProjector projector = new BfsValueProjector();
  private static long nextId = 1;

  @Test
  void wrappedDequeRetainsFifoOrder() {
    var q = object("java.util.ArrayDeque");
    field(q, "elements", array("java.lang.Object[]", integer(8), null, null, integer(3)));
    field(q, "head", number(3));
    field(q, "tail", number(1));
    assertThat(projector.project(q, "queue", ""))
        .containsEntry("values", List.of(3, 8))
        .containsEntry("length", 2)
        .containsEntry("status", "AVAILABLE");
  }

  @Test
  void emptyDequeIsAvailable() {
    var q = deque(4, 2, 2);
    assertThat(projector.project(q, "queue", ""))
        .containsEntry("values", List.of())
        .containsEntry("length", 0)
        .containsEntry("status", "AVAILABLE");
  }

  @Test
  void invalidOrMissingDequeHeadersAreUnknown() {
    assertThat(projector.project(deque(4, -1, 1), "queue", "")).containsEntry("status", "UNKNOWN");
    assertThat(projector.project(object("java.util.ArrayDeque"), "queue", ""))
        .containsEntry("status", "UNKNOWN");
  }

  @Test
  void nonNullDequeTailSentinelIsUnknown() {
    var q = object("java.util.ArrayDeque");
    field(q, "elements", array("java.lang.Object[]", integer(2), integer(9), null));
    field(q, "head", number(0));
    field(q, "tail", number(1));
    assertThat(projector.project(q, "queue", "")).containsEntry("status", "UNKNOWN");
  }

  @Test
  void nullIntegerAndSubclassesAreUnknown() {
    var q = deque(4, 0, 1);
    assertThat(projector.project(q, "queue", "")).containsEntry("status", "UNKNOWN");
    assertThat(projector.project(object("example.Queue"), "queue", ""))
        .containsEntry("status", "UNKNOWN");
  }

  @Test
  void linkedQueueAndCycleValidation() {
    var second = node(integer(4), null);
    var first = node(integer(1), second);
    var q = linked(2, first);
    assertThat(projector.project(q, "queue", ""))
        .containsEntry("values", List.of(1, 4))
        .containsEntry("status", "AVAILABLE");
    field(second, "next", first);
    assertThat(projector.project(q, "queue", "")).containsEntry("status", "UNKNOWN");
    field(q, "size", number(3));
    assertThat(projector.project(q, "queue", "")).containsEntry("status", "UNKNOWN");
  }

  @Test
  void emptyLinkedListAndMalformedSize() {
    assertThat(projector.project(linked(0, null), "queue", ""))
        .containsEntry("values", List.of())
        .containsEntry("status", "AVAILABLE");
    assertThat(projector.project(linked(-1, null), "queue", "")).containsEntry("status", "UNKNOWN");
  }

  @Test
  void queueReadsAreCapped() {
    var storage = mock(ArrayReference.class);
    when(storage.length()).thenReturn(10000);
    var two = integer(2);
    when(storage.getValue(anyInt())).thenReturn(two);
    var q = object("java.util.ArrayDeque");
    field(q, "elements", storage);
    field(q, "head", number(0));
    field(q, "tail", number(9999));
    assertThat(projector.project(q, "queue", ""))
        .containsEntry("status", "TRUNCATED")
        .containsEntry("length", 9999)
        .containsEntry("truncated", true);
    verify(storage, atMost(100)).getValue(anyInt());
    verify(storage, never()).getValues();
  }

  @Test
  void matrixReadsNonzeroDirectedEdges() {
    var graph =
        array(
            "int[][]", array("int[]", number(0), number(2)), array("int[]", number(-1), number(0)));
    assertThat(projector.project(graph, "graph", "ADJACENCY_MATRIX"))
        .containsEntry("vertexCount", 2)
        .containsEntry("status", "AVAILABLE")
        .containsEntry("edges", List.of(Map.of("from", 0, "to", 1), Map.of("from", 1, "to", 0)));
  }

  @Test
  void malformedMatrixAndNullRowAreUnknown() {
    assertThat(
            projector.project(
                array("int[][]", array("int[]", number(1), number(0))),
                "graph",
                "ADJACENCY_MATRIX"))
        .containsEntry("status", "UNKNOWN");
    assertThat(projector.project(array("int[][]", (Value) null), "graph", "ADJACENCY_MATRIX"))
        .containsEntry("status", "UNKNOWN");
  }

  @Test
  void adjacencyListsSupportExactArrayAndLinkedLists() {
    var graph = list(list(integer(1), integer(1)), linked(1, node(integer(0), null)));
    assertThat(projector.project(graph, "graph", "ADJACENCY_LIST"))
        .containsEntry("vertexCount", 2)
        .containsEntry("status", "AVAILABLE")
        .containsEntry(
            "edges",
            List.of(
                Map.of("from", 0, "to", 1),
                Map.of("from", 0, "to", 1),
                Map.of("from", 1, "to", 0)));
  }

  @Test
  void adjacencyOutOfBoundsNullAndCustomRowsAreUnknown() {
    for (Value row :
        new Value[] {list(integer(1)), list((Value) null), object("example.List"), null}) {
      assertThat(projector.project(list(row), "graph", "ADJACENCY_LIST"))
          .containsEntry("status", "UNKNOWN");
    }
  }

  @Test
  void visitedPreservesFalseEntriesAndTruncates() {
    var yes = mock(BooleanValue.class);
    when(yes.value()).thenReturn(true);
    var no = mock(BooleanValue.class);
    when(no.value()).thenReturn(false);
    assertThat(projector.project(array("boolean[]", yes, no), "visited", ""))
        .containsEntry("values", List.of(true, false))
        .containsEntry("status", "AVAILABLE");
    var huge = mock(ArrayReference.class);
    when(huge.length()).thenReturn(10000);
    var type = mock(ReferenceType.class);
    when(type.name()).thenReturn("boolean[]");
    when(huge.referenceType()).thenReturn(type);
    when(huge.getValue(anyInt())).thenReturn(no);
    assertThat(projector.project(huge, "visited", "")).containsEntry("status", "TRUNCATED");
    verify(huge, atMost(100)).getValue(anyInt());
  }

  @Test
  void nestedMatrixSharesReadBudget() {
    var row = mock(ArrayReference.class);
    when(row.length()).thenReturn(100);
    var rowType = mock(ReferenceType.class);
    when(rowType.name()).thenReturn("int[]");
    when(row.referenceType()).thenReturn(rowType);
    var one = number(1);
    when(row.getValue(anyInt())).thenReturn(one);
    var rows = new Value[100];
    Arrays.fill(rows, row);
    assertThat(projector.project(array("int[][]", rows), "graph", "ADJACENCY_MATRIX"))
        .containsEntry("status", "TRUNCATED");
    verify(row, atMost(1000)).getValue(anyInt());
  }

  @Test
  void nullAndJdiFailuresAreUnknown() {
    assertThat(projector.project(null, "queue", "")).containsEntry("status", "UNKNOWN");
    var broken = mock(ObjectReference.class);
    when(broken.referenceType()).thenThrow(new RuntimeException());
    assertThat(projector.project(broken, "queue", "")).containsEntry("status", "UNKNOWN");
  }

  private static ObjectReference deque(int capacity, int head, int tail) {
    var q = object("java.util.ArrayDeque");
    field(q, "elements", array("java.lang.Object[]", new Value[capacity]));
    field(q, "head", number(head));
    field(q, "tail", number(tail));
    return q;
  }

  private static ObjectReference linked(int size, ObjectReference first) {
    var q = object("java.util.LinkedList");
    field(q, "size", number(size));
    field(q, "first", first);
    return q;
  }

  private static ObjectReference node(Value item, ObjectReference next) {
    var n = object("java.util.LinkedList$Node");
    field(n, "item", item);
    field(n, "next", next);
    return n;
  }

  private static ObjectReference list(Value... values) {
    var o = object("java.util.ArrayList");
    field(o, "size", number(values.length));
    field(o, "elementData", array("java.lang.Object[]", values));
    return o;
  }

  private static ObjectReference integer(int value) {
    var o = object("java.lang.Integer");
    field(o, "value", number(value));
    return o;
  }

  private static IntegerValue number(int value) {
    var n = mock(IntegerValue.class);
    when(n.value()).thenReturn(value);
    return n;
  }

  private static ObjectReference object(String name) {
    var o = mock(ObjectReference.class);
    var t = mock(ReferenceType.class);
    when(t.name()).thenReturn(name);
    when(o.referenceType()).thenReturn(t);
    when(o.uniqueID()).thenReturn(nextId++);
    return o;
  }

  private static void field(ObjectReference o, String name, Value value) {
    var f = mock(Field.class);
    when(o.referenceType().fieldByName(name)).thenReturn(f);
    when(o.getValue(f)).thenReturn(value);
  }

  private static ArrayReference array(String typeName, Value... values) {
    var a = mock(ArrayReference.class);
    var t = mock(ReferenceType.class);
    when(t.name()).thenReturn(typeName);
    when(a.referenceType()).thenReturn(t);
    when(a.uniqueID()).thenReturn(nextId++);
    when(a.length()).thenReturn(values.length);
    when(a.getValue(anyInt())).thenAnswer(inv -> values[(int) inv.getArgument(0)]);
    return a;
  }
}
