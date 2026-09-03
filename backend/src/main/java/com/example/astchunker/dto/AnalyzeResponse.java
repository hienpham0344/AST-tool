package com.example.astchunker.dto;

import java.util.List;
import java.util.Map;

/**
 * Response tra ve cho frontend. Tach rieng "chunks" (du lieu chinh) va
 * "warnings" (loi parse khong nghiem trong, vi du parser co the recover
 * mot phan) de UI co the vua hien ket qua vua canh bao nguoi dung.
 */
public class AnalyzeResponse {

    private List<CodeChunkDto> chunks;
    private Map<String, Integer> stats; // vi du: {"class":1,"method":3,"field":2}
    private List<String> warnings;

    public AnalyzeResponse() {
    }

    public AnalyzeResponse(List<CodeChunkDto> chunks, Map<String, Integer> stats, List<String> warnings) {
        this.chunks = chunks;
        this.stats = stats;
        this.warnings = warnings;
    }

    public List<CodeChunkDto> getChunks() {
        return chunks;
    }

    public void setChunks(List<CodeChunkDto> chunks) {
        this.chunks = chunks;
    }

    public Map<String, Integer> getStats() {
        return stats;
    }

    public void setStats(Map<String, Integer> stats) {
        this.stats = stats;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
}
