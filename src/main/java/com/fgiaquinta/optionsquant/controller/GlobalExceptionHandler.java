package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.UncheckedIOException;

/**
 * Global exception handler for the candle API.
 * Ensures consistent error responses and proper logging.
 */
@Slf4j
@RestControllerAdvice(basePackageClasses = CandleController.class)
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MetricsService metrics;

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<CandleApiResponses.ErrorResponse> handleIllegalState(IllegalStateException ex) {
        log.warn(">>> Business error: {}", ex.getMessage());
        metrics.incrementIbkrError("illegal_state");
        return ResponseEntity.badRequest()
                .body(new CandleApiResponses.ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<CandleApiResponses.ErrorResponse> handleIO(UncheckedIOException ex) {
        log.error(">>> IO error: {}", ex.getMessage(), ex);
        metrics.incrementIbkrError("io_error");
        return ResponseEntity.internalServerError()
                .body(new CandleApiResponses.ErrorResponse("File operation failed: " + ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CandleApiResponses.ErrorResponse> handleGeneric(Exception ex) {
        log.error(">>> Unexpected error", ex);
        metrics.incrementIbkrError("unexpected");
        return ResponseEntity.internalServerError()
                .body(new CandleApiResponses.ErrorResponse("Internal server error"));
    }
}
