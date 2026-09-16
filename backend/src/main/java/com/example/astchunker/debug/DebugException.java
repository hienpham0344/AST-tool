package com.example.astchunker.debug;

/** A user-actionable failure while compiling or observing a supplied debug target. */
public class DebugException extends RuntimeException {

  public DebugException(String message) {
    super(message);
  }

  public DebugException(String message, Throwable cause) {
    super(message, cause);
  }
}
