package com.example.astchunker.controller;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.JdiSession;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.dto.DebugStepsResponse;
import com.example.astchunker.model.ObservationResult;
import com.example.astchunker.service.UploadedJavaSourceReader;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Executes one uploaded Java program and returns local variable observations after it exits. */
@RestController
@RequestMapping("/api")
public class DebugController {

  private final UploadedJavaSourceReader sourceReader;
  private final AstAnalyzer astAnalyzer;
  private final TargetCompiler targetCompiler;
  private final JdiSession jdiSession;

  public DebugController(
      UploadedJavaSourceReader sourceReader,
      AstAnalyzer astAnalyzer,
      TargetCompiler targetCompiler,
      JdiSession jdiSession) {
    this.sourceReader = sourceReader;
    this.astAnalyzer = astAnalyzer;
    this.targetCompiler = targetCompiler;
    this.jdiSession = jdiSession;
  }

  @PostMapping(value = "/debug", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<List<ObservationResult>> debug(@RequestParam("file") MultipartFile file) {
    String sourceCode = sourceReader.read(file);
    AstAnalyzer.Analysis analysis = astAnalyzer.analyze(sourceCode);
    try (TargetCompiler.CompiledTarget target = targetCompiler.compile(sourceCode, analysis)) {
      JdiSession.DebugRun run = jdiSession.observe(target, analysis);
      // TODO: Publish each breakpoint observation over WebSocket when the UI needs live streaming.
      return ResponseEntity.ok(run.results());
    }
  }

  @PostMapping(value = "/debug/steps", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<DebugStepsResponse> debugSteps(
      @RequestParam("file") MultipartFile file) {
    String sourceCode = sourceReader.read(file);
    AstAnalyzer.Analysis analysis = astAnalyzer.analyze(sourceCode);
    try (TargetCompiler.CompiledTarget target = targetCompiler.compile(sourceCode, analysis)) {
      JdiSession.DebugRun run = jdiSession.observe(target, analysis);
      return ResponseEntity.ok(new DebugStepsResponse(run.executions(), run.warnings()));
    }
  }
}
