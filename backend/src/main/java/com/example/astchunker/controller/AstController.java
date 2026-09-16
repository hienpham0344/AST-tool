package com.example.astchunker.controller;

import com.example.astchunker.dto.AstParseResponse;
import com.example.astchunker.service.AstService;
import com.example.astchunker.service.UploadedJavaSourceReader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ast")
public class AstController {

  private final AstService astService;
  private final UploadedJavaSourceReader sourceReader;

  public AstController(AstService astService, UploadedJavaSourceReader sourceReader) {
    this.astService = astService;
    this.sourceReader = sourceReader;
  }

  @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<AstParseResponse> parse(@RequestParam("file") MultipartFile file) {
    return ResponseEntity.ok(AstParseResponse.success(astService.parse(sourceReader.read(file))));
  }
}
