package com.example.astchunker.controller;

import com.example.astchunker.dto.AnalyzeRequest;
import com.example.astchunker.dto.AnalyzeResponse;
import com.example.astchunker.service.JavaCodeAnalyzerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * CHU Y KHI XAY DUNG LOGIC O TANG CONTROLLER:
 * - Controller CHI lam nhiem vu: nhan request -> validate -> goi Service -> tra response.
 *   KHONG dat logic parse/AST o day; giu Controller "mong" (thin controller) de de test
 *   Service doc lap (unit test khong can boot Spring MVC).
 * - Neu sau nay can phan biet nhieu ngon ngu (Java/Python/JS...), nen tach route theo
 *   dang "/api/analyze/java", "/api/analyze/python" hoac dua field "language" vao
 *   AnalyzeRequest roi dispatch trong 1 Service tong (Strategy pattern) - KHONG nhoi
 *   if/else theo ngon ngu ngay trong Controller.
 * - Neu API nay public/khong xac thuc, can ran rate-limiting (vi du Bucket4j hoac
 *   gateway/API GW) vi moi request deu ton CPU de parse - de bi lam DoS.
 */
@RestController
@RequestMapping("/api")
public class AnalyzeController {

    private final JavaCodeAnalyzerService analyzerService;

    public AnalyzeController(JavaCodeAnalyzerService analyzerService) {
        this.analyzerService = analyzerService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<AnalyzeResponse> analyze(@Valid @RequestBody AnalyzeRequest request) {
        AnalyzeResponse response = analyzerService.analyze(request.getCode());
        return ResponseEntity.ok(response);
    }
}
