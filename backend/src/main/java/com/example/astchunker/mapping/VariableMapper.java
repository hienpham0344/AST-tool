package com.example.astchunker.mapping;

import com.example.astchunker.model.AstVariable;
import com.example.astchunker.model.ObservationPoint;
import com.example.astchunker.model.ObservationResult;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Converts JDI-visible locals into result records that point back to their AST declarations. */
@Component
public class VariableMapper {

  private static final String UNAVAILABLE_METADATA = "<unavailable>";
  private static final int MAX_ARRAY_ELEMENTS = 100;
  private static final int MAX_NESTING_DEPTH = 3;
  private static final int MAX_STRING_LENGTH = 2_000;
  private static final int MAX_TOTAL_ARRAY_VALUES = 1_000;
  private static final int MAX_LINKED_NODES = 100;
  private static final int MAX_TREE_NODES = 100;
  private static final int MAX_MAP_ENTRIES = 100;

  public List<ObservationResult> map(
      StackFrame frame, ObservationPoint observationPoint, List<AstVariable> astVariables)
    throws AbsentInformationException {
    List<LocalVariable> visibleVariables = frame.visibleVariables();
    List<ObservationResult> results = new ArrayList<>();
    for (LocalVariable variable : visibleVariables) {
      String variableName = readVariableName(variable);
      String jdiTypeName = readTypeName(variable);
      Optional<AstVariable> astVariable =
          findAstVariable(
              variableName, jdiTypeName, observationPoint.lineNumber(), astVariables);
      String runtimeValue;
      try {
        runtimeValue = serialize(frame.getValue(variable));
      } catch (RuntimeException ex) {
        runtimeValue = "[unavailable]";
      }
      results.add(
          new ObservationResult(
              astVariable.map(AstVariable::astNodeId).orElse(observationPoint.astNodeId()),
              variableName,
              astVariable.map(AstVariable::declaredType).orElse(jdiTypeName),
              runtimeValue,
              observationPoint.lineNumber()));
    }
    return List.copyOf(results);
  }

  private String readVariableName(LocalVariable variable) {
    try {
      return variable.name();
    } catch (RuntimeException ex) {
      return UNAVAILABLE_METADATA;
    }
  }

  private String readTypeName(LocalVariable variable) {
    try {
      return variable.typeName();
    } catch (RuntimeException ex) {
      return UNAVAILABLE_METADATA;
    }
  }

  /**
   * JDI does not expose a LocalVariable start/end line range. Its visibleVariables() result is
   * already filtered by the runtime local-variable table for the current frame. AST ranges then
   * select the innermost matching source declaration, which resolves shadowed names safely.
   */
  public Optional<AstVariable> findAstVariable(
      String variableName, String jdiTypeName, int eventLine, List<AstVariable> astVariables) {
    return astVariables.stream()
        .filter(variable -> variable.name().equals(variableName))
        .filter(variable -> typesMatch(variable.declaredType(), jdiTypeName))
        .filter(variable -> variable.isInScopeAt(eventLine))
        .min(
            Comparator.comparingInt(AstVariable::scopeWidth)
                .thenComparing(Comparator.comparingInt(AstVariable::declarationLine).reversed()));
  }

  private boolean typesMatch(String sourceType, String jdiTypeName) {
    String normalizedSource = eraseGenerics(sourceType);
    String normalizedJdi = eraseGenerics(jdiTypeName);
    if (normalizedSource.equals("var")) {
      return true;
    }
    return normalizedSource.equals(normalizedJdi)
        || simpleName(normalizedSource).equals(simpleName(normalizedJdi));
  }

  private String eraseGenerics(String typeName) {
    int genericStart = typeName.indexOf('<');
    return (genericStart >= 0 ? typeName.substring(0, genericStart) : typeName).trim();
  }

  private String simpleName(String typeName) {
    int lastDot = typeName.lastIndexOf('.');
    return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
  }

  private String serialize(Value value) {
    return serialize(value, new FormatContext(), 0);
  }

  private String serialize(Value value, FormatContext context, int depth) {
    if (value == null) {
      return "null";
    }
    if (value instanceof StringReference stringReference) {
      try {
        return serializeString(stringReference.value());
      } catch (RuntimeException ex) {
        return referenceIdentity(stringReference) + "[unavailable]";
      }
    }
    if (value instanceof CharValue charValue) {
      try {
        return "'" + escape(String.valueOf(charValue.value()), '\'') + "'";
      } catch (RuntimeException ex) {
        return "<unavailable>";
      }
    }
    if (value instanceof PrimitiveValue) {
      return value.toString();
    }
    if (value instanceof ArrayReference arrayReference) {
      return serializeArray(arrayReference, context, depth);
    }
    if (value instanceof ObjectReference objectReference) {
      return serializeObject(objectReference, context, depth);
    }
    return value.toString();
  }

  private String serializeString(String value) {
    boolean truncated = value.length() > MAX_STRING_LENGTH;
    String visible = truncated ? value.substring(0, MAX_STRING_LENGTH) : value;
    if (truncated && !visible.isEmpty() && Character.isHighSurrogate(visible.charAt(visible.length() - 1))) {
      visible = visible.substring(0, visible.length() - 1);
    }
    String result = "\"" + escape(visible, '"');
    if (truncated) {
      result += "...\" (length=" + value.length() + ")";
    } else {
      result += "\"";
    }
    return result;
  }

  private String serializeArray(
      ArrayReference arrayReference, FormatContext context, int depth) {
    if (depth >= MAX_NESTING_DEPTH) {
      return "<max-depth>";
    }

    long identity;
    try {
      identity = arrayReference.uniqueID();
    } catch (RuntimeException ex) {
      return referenceIdentity(arrayReference) + "[unavailable]";
    }

    if (!context.activeArrayIds.add(identity)) {
      return "<cycle>";
    }

    try {
      int length = arrayReference.length();
      int count =
          Math.min(
              Math.min(length, MAX_ARRAY_ELEMENTS),
              context.remainingArrayValues);
      context.remainingArrayValues -= count;

      List<Value> values = count == 0 ? List.of() : arrayReference.getValues(0, count);
      StringBuilder result = new StringBuilder("[");
      for (int index = 0; index < values.size(); index++) {
        if (index > 0) {
          result.append(", ");
        }
        result.append(serialize(values.get(index), context, depth + 1));
      }

      boolean truncated = values.size() < length;
      if (truncated) {
        if (!values.isEmpty()) {
          result.append(", ");
        }
        result.append("...");
      }
      result.append("]");
      if (truncated) {
        result.append(" (length=").append(length).append(")");
      }
      return result.toString();
    } catch (RuntimeException ex) {
      return referenceIdentity(arrayReference) + "[unavailable]";
    } finally {
      context.activeArrayIds.remove(identity);
    }
  }

  private String serializeObject(
      ObjectReference objectReference, FormatContext context, int depth) {
    if (depth >= MAX_NESTING_DEPTH) {
      return referenceIdentity(objectReference);
    }

    long identity;
    try {
      identity = objectReference.uniqueID();
    } catch (RuntimeException ex) {
      return referenceIdentity(objectReference) + "[unavailable]";
    }

    if (!context.activeObjectIds.add(identity)) {
      return "<cycle>";
    }

    try {
      String typeName = objectReference.referenceType().name();
      if (isListNode(typeName, objectReference)) {
        return serializeListNode(objectReference, context, depth);
      }
      if (isTreeNode(typeName, objectReference)) {
        TreeBudget budget = new TreeBudget();
        context.activeObjectIds.remove(identity);
        return serializeTreeNode(objectReference, context, depth, budget);
      }
      if (isArrayList(typeName)) {
        return serializeArrayList(objectReference, context, depth);
      }
      if (isLinkedList(typeName)) {
        return serializeLinkedList(objectReference, context, depth);
      }
      if (isHashSet(typeName)) {
        return serializeHashSet(objectReference, context, depth);
      }
      if (isArrayDeque(typeName)) {
        return serializeArrayDeque(objectReference, context, depth);
      }
      if (isHashMap(typeName)) {
        return serializeHashMap(objectReference, context, depth);
      }
      // Invoking Object.toString() can run arbitrary target code while all threads are suspended.
      // A reference identity is deterministic and avoids causing side effects in the debuggee.
      return referenceIdentity(objectReference);
    } catch (RuntimeException ex) {
      return referenceIdentity(objectReference) + "[unavailable]";
    } finally {
      context.activeObjectIds.remove(identity);
    }
  }

  private boolean isArrayList(String typeName) {
    return typeName.equals("java.util.ArrayList");
  }

  private boolean isLinkedList(String typeName) {
    return typeName.equals("java.util.LinkedList");
  }

  private boolean isHashSet(String typeName) {
    return typeName.equals("java.util.HashSet") || typeName.equals("java.util.LinkedHashSet");
  }

  private boolean isArrayDeque(String typeName) {
    return typeName.equals("java.util.ArrayDeque");
  }

  private boolean isHashMap(String typeName) {
    return typeName.equals("java.util.HashMap") || typeName.equals("java.util.LinkedHashMap");
  }

  private boolean isListNode(String typeName, ObjectReference objectReference) {
    return simpleName(typeName).equals("ListNode")
        || (findField(objectReference, "next") != null
            && (findField(objectReference, "val") != null || findField(objectReference, "value") != null));
  }

  private boolean isTreeNode(String typeName, ObjectReference objectReference) {
    return simpleName(typeName).equals("TreeNode")
        || (findField(objectReference, "left") != null
            && findField(objectReference, "right") != null
            && (findField(objectReference, "val") != null || findField(objectReference, "value") != null));
  }

  private String serializeArrayList(
      ObjectReference list, FormatContext context, int depth) {
    int size = intField(list, "size", MAX_ARRAY_ELEMENTS);
    Value elementData = fieldValue(list, "elementData");
    if (!(elementData instanceof ArrayReference elements)) {
      return referenceIdentity(list) + "[unavailable]";
    }
    int count = Math.min(Math.min(size, MAX_ARRAY_ELEMENTS), context.remainingArrayValues);
    context.remainingArrayValues -= count;
    return serializeIndexedValues(elements.getValues(0, count), size, context, depth + 1);
  }

  private String serializeArrayDeque(
      ObjectReference deque, FormatContext context, int depth) {
    Value elementsValue = fieldValue(deque, "elements");
    if (!(elementsValue instanceof ArrayReference elements)) {
      return referenceIdentity(deque) + "[unavailable]";
    }
    int head = intField(deque, "head", 0);
    int tail = intField(deque, "tail", 0);
    int capacity = elements.length();
    int size = tail >= head ? tail - head : capacity - head + tail;
    int count = Math.min(Math.min(size, MAX_ARRAY_ELEMENTS), context.remainingArrayValues);
    context.remainingArrayValues -= count;

    List<Value> values = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      values.add(elements.getValue((head + index) % capacity));
    }
    return serializeIndexedValues(values, size, context, depth + 1);
  }

  private String serializeLinkedList(
      ObjectReference list, FormatContext context, int depth) {
    int size = intField(list, "size", MAX_LINKED_NODES);
    Value first = fieldValue(list, "first");
    List<Value> values = new ArrayList<>();
    ObjectReference node = first instanceof ObjectReference object ? object : null;
    Set<Long> seenNodes = new HashSet<>();
    while (node != null && values.size() < Math.min(size, MAX_LINKED_NODES)) {
      long id = node.uniqueID();
      if (!seenNodes.add(id)) {
        break;
      }
      values.add(fieldValue(node, "item"));
      Value next = fieldValue(node, "next");
      node = next instanceof ObjectReference nextNode ? nextNode : null;
    }
    return serializeIndexedValues(values, size, context, depth + 1);
  }

  private String serializeHashSet(
      ObjectReference set, FormatContext context, int depth) {
    Value mapValue = fieldValue(set, "map");
    if (!(mapValue instanceof ObjectReference map)) {
      return referenceIdentity(set) + "[unavailable]";
    }
    return serializeHashMapEntries(map, context, depth, false);
  }

  private String serializeHashMap(
      ObjectReference map, FormatContext context, int depth) {
    return serializeHashMapEntries(map, context, depth, true);
  }

  private String serializeHashMapEntries(
      ObjectReference map, FormatContext context, int depth, boolean includeValues) {
    int size = intField(map, "size", MAX_MAP_ENTRIES);
    Value tableValue = fieldValue(map, "table");
    if (!(tableValue instanceof ArrayReference table)) {
      return includeValues ? "{}" : "[]";
    }

    List<String> entries = new ArrayList<>();
    Set<Long> seenNodes = new HashSet<>();
    int tableLength = table.length();
    for (int bucket = 0; bucket < tableLength && entries.size() < MAX_MAP_ENTRIES; bucket++) {
      Value bucketValue = table.getValue(bucket);
      ObjectReference node = bucketValue instanceof ObjectReference object ? object : null;
      while (node != null && entries.size() < MAX_MAP_ENTRIES) {
        long id = node.uniqueID();
        if (!seenNodes.add(id)) {
          break;
        }
        String key = serialize(fieldValue(node, "key"), context, depth + 1);
        if (includeValues) {
          String value = serialize(fieldValue(node, "value"), context, depth + 1);
          entries.add(key + ": " + value);
        } else {
          entries.add(key);
        }
        Value next = fieldValue(node, "next");
        node = next instanceof ObjectReference nextNode ? nextNode : null;
      }
    }

    String open = includeValues ? "{" : "[";
    String close = includeValues ? "}" : "]";
    String result = open + String.join(", ", entries);
    if (entries.size() < size) {
      result += entries.isEmpty() ? "..." : ", ...";
    }
    return result + close;
  }

  private String serializeListNode(
      ObjectReference head, FormatContext context, int depth) {
    StringBuilder result = new StringBuilder("[");
    ObjectReference node = head;
    Set<Long> seenNodes = new HashSet<>();
    int count = 0;
    while (node != null && count < MAX_LINKED_NODES) {
      long id = node.uniqueID();
      if (!seenNodes.add(id)) {
        if (count > 0) {
          result.append(" -> ");
        }
        result.append("<cycle>");
        break;
      }
      if (count > 0) {
        result.append(" -> ");
      }
      result.append(serialize(listOrTreeValue(node), context, depth + 1));
      Value next = fieldValue(node, "next");
      node = next instanceof ObjectReference nextNode ? nextNode : null;
      count++;
    }
    if (node != null && count >= MAX_LINKED_NODES) {
      result.append(" -> ...");
    }
    return result.append("]").toString();
  }

  private String serializeTreeNode(
      ObjectReference node, FormatContext context, int depth, TreeBudget budget) {
    if (node == null) {
      return "null";
    }
    if (depth >= MAX_NESTING_DEPTH || budget.remaining-- <= 0) {
      return referenceIdentity(node);
    }
    long identity = node.uniqueID();
    if (!context.activeObjectIds.add(identity)) {
      return "<cycle>";
    }
    try {
      Value left = fieldValue(node, "left");
      Value right = fieldValue(node, "right");
      String value = serialize(listOrTreeValue(node), context, depth + 1);
      return "{val: "
          + value
          + ", left: "
          + serializeTreeNode(left instanceof ObjectReference object ? object : null, context, depth + 1, budget)
          + ", right: "
          + serializeTreeNode(right instanceof ObjectReference object ? object : null, context, depth + 1, budget)
          + "}";
    } finally {
      context.activeObjectIds.remove(identity);
    }
  }

  private Value listOrTreeValue(ObjectReference node) {
    Value value = fieldValue(node, "val");
    return value != null ? value : fieldValue(node, "value");
  }

  private String serializeIndexedValues(
      List<Value> values, int totalSize, FormatContext context, int depth) {
    StringBuilder result = new StringBuilder("[");
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) {
        result.append(", ");
      }
      result.append(serialize(values.get(index), context, depth));
    }
    if (values.size() < totalSize) {
      if (!values.isEmpty()) {
        result.append(", ");
      }
      result.append("...");
    }
    return result.append("]").toString();
  }

  private int intField(ObjectReference objectReference, String fieldName, int fallback) {
    Value value = fieldValue(objectReference, fieldName);
    if (value instanceof PrimitiveValue primitiveValue) {
      try {
        return Integer.parseInt(primitiveValue.toString());
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private Value fieldValue(ObjectReference objectReference, String fieldName) {
    Field field = findField(objectReference, fieldName);
    return field == null ? null : objectReference.getValue(field);
  }

  private Field findField(ObjectReference objectReference, String fieldName) {
    try {
      ReferenceType type = objectReference.referenceType();
      while (type != null) {
        Field field = type.fieldByName(fieldName);
        if (field != null) {
          return field;
        }
        type = type instanceof ClassType classType ? classType.superclass() : null;
      }
    } catch (RuntimeException ignored) {
      return null;
    }
    return null;
  }

  private String referenceIdentity(ObjectReference reference) {
    try {
      return reference.referenceType().name() + "#" + reference.uniqueID();
    } catch (RuntimeException ex) {
      return "<unavailable-reference>";
    }
  }

  private String escape(String value, char quote) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format("\\u%04x", (int) character));
          } else {
            if (character == quote) {
              escaped.append('\\');
            }
            escaped.append(character);
          }
        }
      }
    }
    return escaped.toString();
  }

  private static final class FormatContext {

    private int remainingArrayValues = MAX_TOTAL_ARRAY_VALUES;
    private final Set<Long> activeArrayIds = new HashSet<>();
    private final Set<Long> activeObjectIds = new HashSet<>();
  }

  private static final class TreeBudget {

    private int remaining = MAX_TREE_NODES;
  }
}
