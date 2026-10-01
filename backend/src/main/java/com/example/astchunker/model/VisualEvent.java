package com.example.astchunker.model;

/** An observed snapshot difference, not a claim that a particular statement caused it. */
public record VisualEvent(
    String stateId,
    String type,
    String target,
    Long fromSequence,
    long toSequence,
    Object before,
    Object after) {}
