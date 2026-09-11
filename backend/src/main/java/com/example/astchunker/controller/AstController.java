package com.example.astchunker.controller;

import com.example.astchunker.dto.AnalyzeRequest;
import com.example.astchunker.dto.AstParseResponse;
import com.example.astchunker.service.AstService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ast")
public class AstController {

    private final AstService astService;

    public AstController(AstService astService) {
        this.astService = astService;
    }

    @PostMapping("/parse")
    public ResponseEntity<AstParseResponse> parse(@Valid @RequestBody AnalyzeRequest request) {
        return ResponseEntity.ok(AstParseResponse.success(astService.parse(request.getCode())));
    }
}
