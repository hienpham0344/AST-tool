package com.example.astchunker.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * CHU Y: gom xu ly loi vao 1 cho duy nhat (@RestControllerAdvice) thay vi try/catch
 * rai rac trong Controller. Frontend chi can bat HTTP status (400/422/500) +
 * doc field "message"/"problems" de hien thi, khong can quan tam exception Java cu the.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Loi cu phap Java trong code nguoi dung nhap (khong phai loi he thong) -> 422
    @ExceptionHandler(CodeParseException.class)
    public ResponseEntity<Map<String, Object>> handleParseError(CodeParseException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "message", ex.getMessage(),
                "problems", ex.getProblems()
        ));
    }

    // Loi validate request (vi du code rong, vuot qua do dai cho phep) -> 400
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest().body(Map.of(
                "message", "Du lieu gui len khong hop le",
                "problems", errors
        ));
    }

    // CHU Y: bat them Exception.class chung o day CHI de tra loi dep cho nguoi dung,
    // KHONG duoc lo chi tiet stacktrace/thong tin noi bo ra ngoai (rui ro bao mat).
    // Log chi tiet o server (logger.error(...)) va chi tra thong bao chung cho client.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        // TODO: them logger.error("Unexpected error", ex) o day khi gan logging framework
        return ResponseEntity.internalServerError().body(Map.of(
                "message", "Da co loi khong mong muon xay ra o server"
        ));
    }
}
