package com.codelensai.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("validation failed");
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    /** 401/404/409/422/... raised deliberately (e.g. {@code AccessGuard}'s 404 on a non-member resource). */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onStatus(ResponseStatusException e) {
        HttpStatusCode code = e.getStatusCode();
        HttpStatus status = HttpStatus.resolve(code.value());
        String reason = e.getReason() != null ? e.getReason() : (status == null ? "error" : status.getReasonPhrase());
        return ResponseEntity.status(code).body(body(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status, reason));
    }

    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<Map<String, Object>> onErrorResponse(ErrorResponseException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        return ResponseEntity.status(e.getStatusCode())
                .body(body(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status, e.getBody().getDetail()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "internal error"));
    }

    private Map<String, Object> body(HttpStatus status, String message) {
        return Map.of("status", status.value(), "error", status.getReasonPhrase(), "message", String.valueOf(message));
    }
}
