package com.example.astchunker.debug.operation;

import java.util.List;

public class OperationCoverageException extends RuntimeException {
  private final List<String> problems;

  public OperationCoverageException(List<String> problems) {
    super("Unsupported operation trace coverage: " + String.join("; ", problems));
    this.problems = List.copyOf(problems);
  }

  public List<String> problems() {
    return problems;
  }

  public List<String> getProblems() {
    return problems;
  }
}
