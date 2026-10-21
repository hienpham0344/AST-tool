package com.example.astchunker.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A heuristic visualization suggestion scoped to a source range, not a correctness proof. */
public record AlgorithmHint(
    String type,
    double confidence,
    String visualPlan,
    List<String> evidence,
    Map<String, String> variables,
    int startLine,
    int endLine,
    String patternAstNodeId,
    String methodAstNodeId,
    Map<String, String> variableDeclarationIds,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        SortPattern sort,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        QuickPattern quick,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        MergePattern merge,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        HeapPattern heap,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        ActivitySelectionPattern activitySelection,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        TwoPointersPattern twoPointers,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        SlidingWindowPattern slidingWindow,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        LinearSearchPattern linearSearch,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        BfsPattern bfs,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        PrefixSumPattern prefixSum,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        DfsPattern dfs) {

  public AlgorithmHint(String type, double confidence, String visualPlan,
                       List<String> evidence, Map<String, String> variables,
                       int startLine, int endLine, String patternAstNodeId,
                       String methodAstNodeId, Map<String, String> variableDeclarationIds,
                       SortPattern sort, QuickPattern quick, MergePattern merge,
                       HeapPattern heap, ActivitySelectionPattern activitySelection,
                       TwoPointersPattern twoPointers, SlidingWindowPattern slidingWindow,
                       LinearSearchPattern linearSearch, BfsPattern bfs, PrefixSumPattern prefixSum) {
    this(type, confidence, visualPlan, evidence, variables, startLine, endLine,
        patternAstNodeId, methodAstNodeId, variableDeclarationIds, sort, quick, merge,
        heap, activitySelection, twoPointers, slidingWindow, linearSearch, bfs, prefixSum, null);
  }

  public AlgorithmHint(String type, double confidence, String visualPlan,
                       List<String> evidence, Map<String, String> variables,
                       int startLine, int endLine, String patternAstNodeId,
                       String methodAstNodeId, Map<String, String> variableDeclarationIds,
                       SortPattern sort, QuickPattern quick, MergePattern merge,
                       HeapPattern heap, ActivitySelectionPattern activitySelection,
                       TwoPointersPattern twoPointers, SlidingWindowPattern slidingWindow,
                       LinearSearchPattern linearSearch, BfsPattern bfs) {
    this(type, confidence, visualPlan, evidence, variables, startLine, endLine,
        patternAstNodeId, methodAstNodeId, variableDeclarationIds, sort, quick, merge,
        heap, activitySelection, twoPointers, slidingWindow, linearSearch, bfs, null);
  }

  public AlgorithmHint(String type, double confidence, String visualPlan,
                       List<String> evidence, Map<String, String> variables,
                       int startLine, int endLine, String patternAstNodeId,
                       String methodAstNodeId, Map<String, String> variableDeclarationIds,
                       SortPattern sort, QuickPattern quick, MergePattern merge,
                       HeapPattern heap, ActivitySelectionPattern activitySelection,
                       TwoPointersPattern twoPointers, SlidingWindowPattern slidingWindow,
                       LinearSearchPattern linearSearch) {
    this(type, confidence, visualPlan, evidence, variables, startLine, endLine,
        patternAstNodeId, methodAstNodeId, variableDeclarationIds, sort, quick, merge,
        heap, activitySelection, twoPointers, slidingWindow, linearSearch, null);
  }

  public AlgorithmHint(String type, double confidence, String visualPlan,
                       List<String> evidence, Map<String, String> variables,
                       int startLine, int endLine, String patternAstNodeId,
                       String methodAstNodeId, Map<String, String> variableDeclarationIds,
                       SortPattern sort, QuickPattern quick, MergePattern merge,
                       HeapPattern heap, ActivitySelectionPattern activitySelection,
                       TwoPointersPattern twoPointers, SlidingWindowPattern slidingWindow) {
    this(type, confidence, visualPlan, evidence, variables, startLine, endLine,
        patternAstNodeId, methodAstNodeId, variableDeclarationIds, sort, quick,
        merge, heap, activitySelection, twoPointers, slidingWindow, null);
  }

  public AlgorithmHint(String type, double confidence, String visualPlan,
                       List<String> evidence, Map<String, String> variables,
                       int startLine, int endLine, String patternAstNodeId,
                       String methodAstNodeId, Map<String, String> variableDeclarationIds,
                       SortPattern sort, QuickPattern quick, MergePattern merge,
                       HeapPattern heap, ActivitySelectionPattern activitySelection,
                       TwoPointersPattern twoPointers) {
    this(type, confidence, visualPlan, evidence, variables, startLine, endLine,
        patternAstNodeId, methodAstNodeId, variableDeclarationIds, sort, quick,
        merge, heap, activitySelection, twoPointers, null);
  }

  public AlgorithmHint(
      String type,
      double confidence,
      String visualPlan,
      List<String> evidence,
      Map<String, String> variables,
      int startLine,
      int endLine,
      String patternAstNodeId,
      String methodAstNodeId,
      Map<String, String> variableDeclarationIds,
      SortPattern sort,
      QuickPattern quick,
      MergePattern merge,
      HeapPattern heap,
      ActivitySelectionPattern activitySelection) {
    this(type, confidence, visualPlan, evidence, variables, startLine, endLine,
        patternAstNodeId, methodAstNodeId, variableDeclarationIds,
        sort, quick, merge, heap, activitySelection, null);
  }

  public AlgorithmHint(
      String type,
      double confidence,
      String visualPlan,
      List<String> evidence,
      Map<String, String> variables,
      int startLine,
      int endLine,
      String patternAstNodeId,
      String methodAstNodeId,
      Map<String, String> variableDeclarationIds,
      SortPattern sort,
      QuickPattern quick,
      MergePattern merge,
      HeapPattern heap) {
    this(type, confidence, visualPlan, evidence, variables, startLine, endLine,
        patternAstNodeId, methodAstNodeId, variableDeclarationIds, sort, quick, merge, heap, null);
  }

  public AlgorithmHint(
      String type,
      double confidence,
      String visualPlan,
      List<String> evidence,
      Map<String, String> variables,
      int startLine,
      int endLine,
      String patternAstNodeId,
      String methodAstNodeId,
      Map<String, String> variableDeclarationIds,
      SortPattern sort,
      QuickPattern quick,
      MergePattern merge) {
    this(
        type,
        confidence,
        visualPlan,
        evidence,
        variables,
        startLine,
        endLine,
        patternAstNodeId,
        methodAstNodeId,
        variableDeclarationIds,
        sort,
        quick,
        merge,
        null,
        null);
  }

  public AlgorithmHint(
      String type,
      double confidence,
      String visualPlan,
      List<String> evidence,
      Map<String, String> variables,
      int startLine,
      int endLine,
      String patternAstNodeId,
      String methodAstNodeId,
      Map<String, String> variableDeclarationIds,
      SortPattern sort,
      QuickPattern quick) {
    this(
        type,
        confidence,
        visualPlan,
        evidence,
        variables,
        startLine,
        endLine,
        patternAstNodeId,
        methodAstNodeId,
        variableDeclarationIds,
        sort,
        quick,
        null,
        null,
        null);
  }

  public AlgorithmHint(
      String type,
      double confidence,
      String visualPlan,
      List<String> evidence,
      Map<String, String> variables,
      int startLine,
      int endLine,
      String patternAstNodeId,
      String methodAstNodeId,
      Map<String, String> variableDeclarationIds,
      SortPattern sort) {
    this(
        type,
        confidence,
        visualPlan,
        evidence,
        variables,
        startLine,
        endLine,
        patternAstNodeId,
        methodAstNodeId,
        variableDeclarationIds,
        sort,
        null,
        null,
        null,
        null);
  }

  public AlgorithmHint(
      String type,
      double confidence,
      String visualPlan,
      List<String> evidence,
      Map<String, String> variables,
      int startLine,
      int endLine,
      String patternAstNodeId,
      String methodAstNodeId,
      Map<String, String> variableDeclarationIds) {
    this(
        type,
        confidence,
        visualPlan,
        evidence,
        variables,
        startLine,
        endLine,
        patternAstNodeId,
        methodAstNodeId,
        variableDeclarationIds,
        null,
        null,
        null,
        null,
        null);
  }

  public AlgorithmHint(
      String type,
      double confidence,
      String visualPlan,
      List<String> evidence,
      Map<String, String> variables,
      int startLine,
      int endLine) {
    this(
        type,
        confidence,
        visualPlan,
        evidence,
        variables,
        startLine,
        endLine,
        null,
        null,
        Map.of());
  }

  public AlgorithmHint {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(visualPlan, "visualPlan");
    if (type.isBlank() || visualPlan.isBlank()) {
      throw new IllegalArgumentException("type and visualPlan must not be blank");
    }
    if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
      throw new IllegalArgumentException("confidence must be finite and between 0 and 1");
    }
    if (startLine < 1 || endLine < startLine) {
      throw new IllegalArgumentException("source range must use ordered, positive line numbers");
    }
    evidence = List.copyOf(evidence);
    variables = Map.copyOf(variables);
    variableDeclarationIds = Map.copyOf(variableDeclarationIds);
    if ((sort != null ? 1 : 0)
            + (quick != null ? 1 : 0)
            + (merge != null ? 1 : 0)
            + (heap != null ? 1 : 0)
        > 1) throw new IllegalArgumentException("One hint cannot have two sort metadata families");
    if (activitySelection != null && (sort != null || quick != null || merge != null || heap != null))
      throw new IllegalArgumentException("One hint cannot mix greedy and sort metadata");
    if (twoPointers != null && (sort != null || quick != null || merge != null || heap != null
        || activitySelection != null))
      throw new IllegalArgumentException("Two-pointer metadata cannot mix with another family");
  }
}
