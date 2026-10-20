package com.example.astchunker.mapping;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.Value;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Field-only BFS snapshots. A shared budget includes every field and array-element read. */
public final class BfsValueProjector {
  private static final int MAX_VERTICES = 100;
  private static final int MAX_QUEUE = 100;
  private static final int MAX_EDGES = 1000;
  private static final int MAX_READS = 1000;

  public Map<String, Object> project(Value value, String role, String representation) {
    Map<String, Object> result = new LinkedHashMap<>();
    List<Object> values = new ArrayList<>();
    boolean graph = "graph".equals(role);
    result.put(graph ? "edges" : "values", values);
    result.put(graph ? "vertexCount" : "length", null);
    result.put("status", "UNKNOWN");
    result.put("truncated", false);
    Budget budget = new Budget();
    try {
      if (graph) {
        if ("ADJACENCY_MATRIX".equals(representation)) matrix(value, result, values, budget);
        else if ("ADJACENCY_LIST".equals(representation)) adjacency(value, result, values, budget);
        else throw new Invalid();
      } else if ("queue".equals(role)) {
        sequence(value, result, values, budget);
      } else if ("visited".equals(role)) {
        ArrayReference array = array(value, "boolean[]");
        int length = length(array);
        result.put("length", length);
        for (int i = 0; i < Math.min(length, MAX_VERTICES); i++) {
          Value element = budget.element(array, i);
          if (!(element instanceof BooleanValue flag)) throw new Invalid();
          values.add(flag.value());
        }
        if (length > MAX_VERTICES) throw new Limited();
      } else throw new Invalid();
      result.put("status", "AVAILABLE");
    } catch (Limited ex) {
      result.put("status", "TRUNCATED");
      result.put("truncated", true);
    } catch (RuntimeException ex) {
      // Inaccessible JDI metadata and malformed target fields are unknown snapshots.
      result.put("status", "UNKNOWN");
    }
    return result;
  }

  private void sequence(
      Value value, Map<String, Object> result, List<Object> values, Budget budget) {
    if (!(value instanceof ObjectReference object)) throw new Invalid();
    if ("java.util.ArrayDeque".equals(type(object))) {
      Value storage = budget.field(object, "elements");
      if (!(storage instanceof ArrayReference array)) throw new Invalid();
      int capacity = length(array);
      int head = integer(budget.field(object, "head"));
      int tail = integer(budget.field(object, "tail"));
      if (capacity < 1 || head < 0 || tail < 0 || head >= capacity || tail >= capacity)
        throw new Invalid();
      int count = (int) (((long) tail - head + capacity) % capacity);
      result.put("length", count);
      for (int i = 0; i < Math.min(count, MAX_QUEUE); i++) {
        int index = (int) (((long) head + i) % capacity);
        values.add(boxedInteger(budget.element(array, index), budget));
      }
      if (count > MAX_QUEUE) throw new Limited();
      // ArrayDeque reserves a null slot at tail, including for the empty ring.
      if (budget.element(array, tail) != null) throw new Invalid();
    } else {
      Cursor cursor = cursor(value, budget, false);
      result.put("length", cursor.size);
      for (int i = 0; i < Math.min(cursor.size, MAX_QUEUE); i++)
        values.add(boxedInteger(cursor.next(), budget));
      if (cursor.size > MAX_QUEUE) throw new Limited();
      cursor.finish();
    }
  }

  private void matrix(Value value, Map<String, Object> result, List<Object> edges, Budget budget) {
    ArrayReference graph = array(value, "int[][]");
    int vertices = length(graph);
    result.put("vertexCount", vertices);
    for (int from = 0; from < Math.min(vertices, MAX_VERTICES); from++) {
      ArrayReference row = array(budget.element(graph, from), "int[]");
      if (length(row) != vertices) throw new Invalid();
      for (int to = 0; to < Math.min(vertices, MAX_VERTICES); to++) {
        if (integer(budget.element(row, to)) != 0) edge(edges, from, to);
      }
    }
    if (vertices > MAX_VERTICES) throw new Limited();
  }

  private void adjacency(
      Value value, Map<String, Object> result, List<Object> edges, Budget budget) {
    Cursor graph = cursor(value, budget, true);
    result.put("vertexCount", graph.size);
    for (int from = 0; from < Math.min(graph.size, MAX_VERTICES); from++) {
      Cursor row = cursor(graph.next(), budget, true);
      for (int j = 0; j < row.size; j++) {
        int to = boxedInteger(row.next(), budget);
        if (to < 0 || to >= graph.size) throw new Invalid();
        edge(edges, from, to);
      }
      row.finish();
    }
    if (graph.size > MAX_VERTICES) throw new Limited();
    graph.finish();
  }

  private void edge(List<Object> edges, int from, int to) {
    if (edges.size() >= MAX_EDGES) throw new Limited();
    edges.add(Map.of("from", from, "to", to));
  }

  private Cursor cursor(Value value, Budget budget, boolean allowArrayList) {
    if (!(value instanceof ObjectReference object)) throw new Invalid();
    String type = type(object);
    if (!"java.util.LinkedList".equals(type)
        && !(allowArrayList && "java.util.ArrayList".equals(type))) throw new Invalid();
    int size = integer(budget.field(object, "size"));
    if (size < 0) throw new Invalid();
    if ("java.util.ArrayList".equals(type)) {
      Value storage = budget.field(object, "elementData");
      if (!(storage instanceof ArrayReference array) || size > length(array)) throw new Invalid();
      return new Cursor(size, array, null, budget);
    }
    Value first = budget.field(object, "first");
    if (first != null && !(first instanceof ObjectReference)) throw new Invalid();
    if ((size == 0) != (first == null)) throw new Invalid();
    return new Cursor(size, null, (ObjectReference) first, budget);
  }

  private static final class Cursor {
    private final int size;
    private final ArrayReference storage;
    private final Budget budget;
    private final Set<Long> seen = new HashSet<>();
    private ObjectReference node;
    private int index;

    private Cursor(int size, ArrayReference storage, ObjectReference node, Budget budget) {
      this.size = size;
      this.storage = storage;
      this.node = node;
      this.budget = budget;
    }

    private Value next() {
      if (index >= size) throw new Invalid();
      if (storage != null) return budget.element(storage, index++);
      if (node == null
          || !"java.util.LinkedList$Node".equals(type(node))
          || !seen.add(node.uniqueID())) throw new Invalid();
      Value value = budget.field(node, "item");
      Value next = budget.field(node, "next");
      if (next != null && !(next instanceof ObjectReference)) throw new Invalid();
      node = (ObjectReference) next;
      index++;
      if (node != null && seen.contains(node.uniqueID())) throw new Invalid();
      return value;
    }

    private void finish() {
      if (index != size || (storage == null && node != null)) throw new Invalid();
    }
  }

  private static final class Budget {
    private int reads;

    private void consume() {
      if (reads++ >= MAX_READS) throw new Limited();
    }

    private Value field(ObjectReference object, String name) {
      consume();
      Field field = object.referenceType().fieldByName(name);
      if (field == null) throw new Invalid();
      return object.getValue(field);
    }

    private Value element(ArrayReference array, int index) {
      consume();
      return array.getValue(index);
    }
  }

  private static String type(ObjectReference object) {
    return object.referenceType().name();
  }

  private static ArrayReference array(Value value, String name) {
    if (!(value instanceof ArrayReference array) || !name.equals(type(array))) throw new Invalid();
    return array;
  }

  private static int length(ArrayReference array) {
    int length = array.length();
    if (length < 0) throw new Invalid();
    return length;
  }

  private static int integer(Value value) {
    if (!(value instanceof IntegerValue integer)) throw new Invalid();
    return integer.value();
  }

  private static int boxedInteger(Value value, Budget budget) {
    if (!(value instanceof ObjectReference object) || !"java.lang.Integer".equals(type(object)))
      throw new Invalid();
    return integer(budget.field(object, "value"));
  }

  private static final class Invalid extends RuntimeException {}

  private static final class Limited extends RuntimeException {}
}
