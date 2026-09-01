package com.example.transactionengine.transaction.api;

import com.example.transactionengine.transaction.exception.IdempotencyConflictException;
import com.example.transactionengine.transaction.exception.TransactionNotFoundException;
import com.example.transactionengine.transaction.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Central exception handler mapping domain and validation errors to ApiError responses.
 */
@RestControllerAdvice
public class TransactionExceptionHandler {

  /**
   * Handles idempotency conflict (409).
   *
   * @param exception exception
   * @param request http request
   * @return error response
   */
  @ExceptionHandler(IdempotencyConflictException.class)
  ResponseEntity<ApiError> handleConflict(
      IdempotencyConflictException exception, HttpServletRequest request) {
    return error(HttpStatus.CONFLICT, exception.getMessage(), request);
  }

  /**
   * Handles transaction not found (404).
   *
   * @param exception exception
   * @param request http request
   * @return error response
   */
  @ExceptionHandler(TransactionNotFoundException.class)
  ResponseEntity<ApiError> handleNotFound(
      TransactionNotFoundException exception, HttpServletRequest request) {
    return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
  }

  /**
   * Handles validation errors (400).
   *
   * @param exception exception
   * @param request http request
   * @return error response
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> handleValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    var message =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .sorted()
            .collect(Collectors.joining(", "));
    return error(HttpStatus.BAD_REQUEST, message, request);
  }

  /**
   * Handles bad request exceptions (400).
   *
   * @param exception exception
   * @param request http request
   * @return error response
   */
  @ExceptionHandler({
    IllegalArgumentException.class,
    MissingRequestHeaderException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ApiError> handleBadRequest(Exception exception, HttpServletRequest request) {
    return error(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
  }

  /**
   * Handles database unavailable (503).
   *
   * @param exception exception
   * @param request http request
   * @return error response
   */
  @ExceptionHandler(DataAccessResourceFailureException.class)
  ResponseEntity<ApiError> handleDatabaseUnavailable(
      DataAccessResourceFailureException exception, HttpServletRequest request) {
    return error(HttpStatus.SERVICE_UNAVAILABLE, "Database is temporarily unavailable", request);
  }

  /**
   * Handles database errors (500).
   *
   * @param exception exception
   * @param request http request
   * @return error response
   */
  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiError> handleDatabaseError(
      DataAccessException exception, HttpServletRequest request) {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "Database operation failed", request);
  }

  /**
   * Handles unexpected errors (500).
   *
   * @param exception exception
   * @param request http request
   * @return error response
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", request);
  }

  private ResponseEntity<ApiError> error(
      HttpStatus status, String message, HttpServletRequest request) {
    var correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
    if (correlationId == null) {
      correlationId = request.getHeader(CorrelationIdFilter.HEADER);
    }
    return ResponseEntity.status(status)
        .body(
            new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message == null ? status.getReasonPhrase() : message,
                request.getRequestURI(),
                correlationId));
  }
}