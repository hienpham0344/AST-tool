package com.example.astchunker.debug.operation;

import com.example.astchunker.model.ObservationResult;
import com.example.astchunker.model.operation.*;
import java.util.Map;

/**
 * Cumulative projected payload budget; values and text are charged for each serialized occurrence.
 */
public final class OperationTraceBudget {
  public static final long MAX_VALUES = 500_000;
  public static final long MAX_TEXT_CHARS = 4_000_000;
  private long values;
  private long textChars;
  private String exceeded;

  public boolean include(Object... payloads) {
    for (Object payload : payloads) if (!consume(payload)) return false;
    return true;
  }

  public String exceeded() {
    return exceeded;
  }

  private boolean consume(Object value) {
    if (++values > MAX_VALUES) {
      exceeded = "VALUES";
      return false;
    }
    if (value instanceof CharSequence text) {
      textChars += text.length();
      if (textChars > MAX_TEXT_CHARS) {
        exceeded = "TEXT_CHARS";
        return false;
      }
    } else if (value instanceof Map<?, ?> map) {
      for (var entry : map.entrySet()) if (!include(entry.getKey(), entry.getValue())) return false;
    } else if (value instanceof Iterable<?> list) {
      for (Object item : list) if (!consume(item)) return false;
    } else if (value instanceof ObservationResult observed) {
      return include(
          observed.astNodeId(),
          observed.variableName(),
          observed.declaredType(),
          observed.runtimeValue(),
          observed.visualType(),
          observed.visualValue(),
          observed.arrayObjectId(),
          observed.objectId());
    } else if (value instanceof OperationVariable variable) {
      return include(
          variable.instanceId(),
          variable.declarationId(),
          variable.scopeInstanceId(),
          variable.name(),
          variable.declaredType(),
          variable.valueStatus(),
          variable.value(),
          variable.objectId());
    } else if (value instanceof OperationObject object) {
      return include(
          object.objectId(),
          object.type(),
          object.values(),
          object.length(),
          object.status(),
          object.truncated(),
          object.observedAtSequence());
    } else if (value instanceof OperationEffect effect) {
      return include(
          effect.type(),
          effect.entityId(),
          effect.before(),
          effect.after(),
          effect.source(),
          effect.target(),
          effect.status());
    }
    return true;
  }
}
