package com.example.astchunker.algorithm.sort;

import com.example.astchunker.model.AlgorithmHint;
import com.github.javaparser.ast.Node;
import java.util.Optional;

/** Detects one conservative sorting pattern from a loop subtree. */
public interface SortPatternDetector {
  Optional<AlgorithmHint> detect(Node loop);
}
