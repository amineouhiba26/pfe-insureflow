// GlobalExceptionHandler.java
package com.insureflow.web.advice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Catches exceptions thrown anywhere in a controller and returns
 * clean JSON error responses instead of Spring's default HTML error page.
 *
 * @RestControllerAdvice means this applies to ALL controllers globally.
 *
 * Without this, if you send an invalid request body you'd get a messy
 * HTML page. With this, you get: {"status":400,"message":"Email is required"}
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Handles @Valid failures — missing or invalid fields in request body
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("Validation error");
        return ResponseEntity.badRequest().body(error(400, message));
    }

    // Handles "not found" cases thrown manually with IllegalArgumentException
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            IllegalArgumentException ex) {
        return ResponseEntity.status(404).body(error(404, ex.getMessage()));
    }

    // Catches everything else — prevents stack traces leaking to the client
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
                .body(error(500, "Internal server error"));
    }

    private Map<String, Object> error(int status, String message) {
        return Map.of(
                "status", status,
                "message", message,
                "timestamp", Instant.now().toString()
        );
    }
}