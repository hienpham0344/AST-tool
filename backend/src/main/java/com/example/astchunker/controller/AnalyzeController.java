package com.example.astchunker.controller;

import com.example.astchunker.dto.AnalyzeResponse;
import com.example.astchunker.service.JavaCodeAnalyzerService;
import com.example.astchunker.service.UploadedJavaSourceReader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * CHU Y KHI XAY DUNG LOGIC O TANG CONTROLLER: - Controller CHI lam nhiem vu: nhan request ->
 * validate -> goi Service -> tra response. KHONG dat logic parse/AST o day; giu Controller "mong"
 * (thin controller) de de test Service doc lap (unit test khong can boot Spring MVC). - Neu sau nay
 * can phan biet nhieu ngon ngu (Java/Python/JS...), nen tach route theo dang "/api/analyze/java",
 * "/api/analyze/python" hoac dua field "language" vao AnalyzeRequest roi dispatch trong 1 Service
 * tong (Strategy pattern) - KHONG nhoi if/else theo ngon ngu ngay trong Controller. - Neu API nay
 * public/khong xac thuc, can ran rate-limiting (vi du Bucket4j hoac gateway/API GW) vi moi request
 * deu ton CPU de parse - de bi lam DoS.
 */
@RestController
@RequestMapping("/api")
public class AnalyzeController {

  private final JavaCodeAnalyzerService analyzerService;
  private final UploadedJavaSourceReader sourceReader;

  public AnalyzeController(
      JavaCodeAnalyzerService analyzerService, UploadedJavaSourceReader sourceReader) {
    this.analyzerService = analyzerService;
    this.sourceReader = sourceReader;
  }

  @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<AnalyzeResponse> analyze(@RequestParam("file") MultipartFile file) {
    AnalyzeResponse response = analyzerService.analyze(sourceReader.read(file));
    return ResponseEntity.ok(response);
  }
}
