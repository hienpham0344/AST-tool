package com.example.astchunker.debug.operation;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.DebugException;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.dto.OperationTraceResponse;
import com.example.astchunker.model.AlgorithmHint;
import com.example.astchunker.visualization.operation.OperationTraceBuilder;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.springframework.stereotype.Service;

/** Explicit operation mode: unsupported coverage never silently degrades to line breakpoints. */
@Service
public class OperationTraceService {
  private final OperationInstrumenter instrumenter;
  private final TargetCompiler compiler;
  private final OperationJdiSession session;
  private final OperationTraceBuilder builder;

  public OperationTraceService(
      OperationInstrumenter instrumenter,
      TargetCompiler compiler,
      OperationJdiSession session,
      OperationTraceBuilder builder) {
    this.instrumenter = instrumenter;
    this.compiler = compiler;
    this.session = session;
    this.builder = builder;
  }

  public OperationTraceResponse trace(
      String source, AstAnalyzer.Analysis analysis, List<AlgorithmHint> hints) {
    // Validate original Java first: instrumentation must not make an invalid program look valid.
    try (var original = compiler.compile(source, analysis)) {
      /* Validation output is discarded. */
    }
    var fields = analysis.compilationUnit().findAll(FieldDeclaration.class);
    if (!fields.isEmpty()) {
      List<String> problems =
          fields.stream()
              .map(
                  field ->
                      field.getRange().orElseThrow()
                          + ": Field-state tracing is unsupported; use local variables or"
                          + " explicitly choose LINE_BREAKPOINT mode.")
              .toList();
      throw new OperationCoverageException(problems);
    }
    OperationProgram program = instrumenter.instrument(source, analysis);
    TargetCompiler.CompiledTarget target;
    try {
      target = compiler.compile(program.sourceCode(), analysis);
    } catch (DebugException failure) {
      throw new OperationCoverageException(
          List.of(
              "1:1-"
                  + source.lines().count()
                  + ":1: Valid original source could not be compiled with operation"
                  + " instrumentation; this construct requires LINE_BREAKPOINT mode."));
    }
    try (target) {
      return builder.build(analysis, hints, session.observe(target, analysis, program));
    }
  }
}
