package com.example.astchunker;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.BreakpointMapper;
import com.example.astchunker.debug.DebugException;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.ObservationResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Command-line entry point for the complete AST to JDI observation pipeline. */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    if (args.length != 1) {
      System.err.println("Usage: Main <source-file.java>");
      return;
    }

    try {
      JdiSession.DebugRun run = inspect(Path.of(args[0]));
      System.out.println(toJson(run.results()));
      run.warnings().forEach(warning -> System.err.println("Warning: " + warning));
    } catch (IOException | DebugException | IllegalArgumentException ex) {
      System.err.println("Debug failed: " + ex.getMessage());
    }
  }

  public static JdiSession.DebugRun inspect(Path sourceFile) throws IOException {
    String sourceCode = Files.readString(sourceFile, StandardCharsets.UTF_8);
    AstAnalyzer astAnalyzer = new AstAnalyzer();
    AstAnalyzer.Analysis analysis = astAnalyzer.analyze(sourceCode);
    TargetCompiler targetCompiler = new TargetCompiler();
    JdiSession jdiSession = new JdiSession(new BreakpointMapper(), new VariableMapper());

    try (TargetCompiler.CompiledTarget target = targetCompiler.compile(sourceCode, analysis)) {
      return jdiSession.observe(target, analysis);
    }
  }

  private static String toJson(List<ObservationResult> results) {
    return results.stream().map(Main::toJson).collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private static String toJson(ObservationResult result) {
    return "{"
        + "\"astNodeId\":"
        + quote(result.astNodeId())
        + ",\"variableName\":"
        + quote(result.variableName())
        + ",\"declaredType\":"
        + quote(result.declaredType())
        + ",\"runtimeValue\":"
        + quote(result.runtimeValue())
        + ",\"lineNumber\":"
        + result.lineNumber()
        + "}";
  }

  private static String quote(String value) {
    return "\""
        + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        + "\"";
  }
}
