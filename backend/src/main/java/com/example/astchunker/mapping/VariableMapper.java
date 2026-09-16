package com.example.astchunker.mapping;

import com.example.astchunker.model.AstVariable;
import com.example.astchunker.model.ObservationPoint;
import com.example.astchunker.model.ObservationResult;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.CharValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
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
      // Invoking Object.toString() can run arbitrary target code while all threads are suspended.
      // A reference identity is deterministic and avoids causing side effects in the debuggee.
      return referenceIdentity(objectReference);
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
  }
}
