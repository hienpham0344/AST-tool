package com.example.astchunker.algorithm.sort;

import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.model.SortPattern;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Legacy ordered detection plus a per-compilation-unit semantic session. */
public final class SortPatternRegistry {
  private final List<SortPatternDetector> detectors;

  public SortPatternRegistry(List<SortPatternDetector> detectors) {
    this.detectors = List.copyOf(detectors);
    if (this.detectors.isEmpty() || this.detectors.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("At least one non-null sort detector is required");
  }

  public static SortPatternRegistry defaults() {
    return new SortPatternRegistry(
        List.of(
            new BubbleSortDetector(), new SelectionSortDetector(), new InsertionSortDetector()));
  }

  public Optional<AlgorithmHint> detect(Node loop) {
    return detectors.stream()
        .map(detector -> detector.detect(loop))
        .flatMap(Optional::stream)
        .findFirst();
  }

  public Session begin(CompilationUnit unit) {
    return new Session(unit);
  }

  public enum Status { UNIQUE, AMBIGUOUS, UNKNOWN }

  public record Evaluation(Status status, List<AlgorithmHint> candidates, List<String> limitations) {
    public Evaluation {
      candidates = List.copyOf(candidates);
      limitations = List.copyOf(limitations);
    }
  }

  public final class Session {
    private final CompilationUnit unit;
    private final SortSemanticFacts.Analysis facts;
    private final SameFileHelperSummaries helpers;

    private Session(CompilationUnit unit) {
      if (unit == null) throw new IllegalArgumentException("Compilation unit is required");
      this.unit = unit;
      facts = SortSemanticFacts.analyze(unit);
      helpers = SameFileHelperSummaries.analyze(unit, facts);
    }

    SortSemanticFacts.Analysis facts() { return facts; }
    SameFileHelperSummaries helpers() { return helpers; }

    public Evaluation evaluate(Node loop) {
      if (loop == null) throw new IllegalArgumentException("Loop is required");
      if (loop.findCompilationUnit().orElse(null) != unit)
        throw new IllegalArgumentException("Loop must belong to the session Compilation unit");
      var unique = new LinkedHashMap<CandidateKey, AlgorithmHint>();
      for (var detector : detectors)
        detector.detect(loop, this)
            .ifPresent(hint -> unique.putIfAbsent(
                new CandidateKey(hint.type(), hint.patternAstNodeId(), hint.sort()), hint));
      var candidates = List.copyOf(unique.values());
      return candidates.isEmpty()
          ? new Evaluation(Status.UNKNOWN, candidates, List.of("no sort detector matched"))
          : candidates.size() == 1
              ? new Evaluation(Status.UNIQUE, candidates, List.of())
              : new Evaluation(Status.AMBIGUOUS, candidates,
                  List.of("multiple distinct sort patterns matched"));
    }
  }

  private record CandidateKey(String type, String patternAstNodeId, SortPattern sort) {}
}
