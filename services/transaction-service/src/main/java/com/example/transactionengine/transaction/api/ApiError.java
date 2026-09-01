package com.example.transactionengine.transaction.api;

import java.time.Instant;

/**
 * Standard API error body with correlation id.
 *
 * @param timestamp error timestamp
 * @param status http status
 * @param error error phrase
 * @param message detailed message
 * @param path request path
 * @param correlationId correlation id
 */
public record ApiError(
    Instant timestamp,
    int status,
    String error,
    String message,
    String path,
    String correlationId) {}