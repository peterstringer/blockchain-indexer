package com.peterstringer.blockchain.indexer.controller;

import com.peterstringer.blockchain.indexer.dto.ErrorResponse;
import com.peterstringer.blockchain.indexer.exception.ChainNotFoundException;
import com.peterstringer.blockchain.indexer.exception.IndexerAlreadyRunningException;
import com.peterstringer.blockchain.indexer.exception.IndexerNotRunningException;
import com.peterstringer.blockchain.indexer.service.RpcClientService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Global exception handler that translates application exceptions into
 * consistent JSON error responses with appropriate HTTP status codes.
 *
 * <p>All error responses follow the {@link ErrorResponse} structure,
 * providing the HTTP status, error name, human-readable message,
 * timestamp, and request path.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles requests referencing a chain that is not configured.
     *
     * @return 404 Not Found
     */
    @ExceptionHandler(ChainNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleChainNotFound(ChainNotFoundException ex,
                                                              HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /**
     * Handles attempts to start an indexer that is already running.
     *
     * @return 409 Conflict
     */
    @ExceptionHandler(IndexerAlreadyRunningException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyRunning(IndexerAlreadyRunningException ex,
                                                               HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * Handles operations that require a running indexer when it is stopped.
     *
     * @return 409 Conflict
     */
    @ExceptionHandler(IndexerNotRunningException.class)
    public ResponseEntity<ErrorResponse> handleNotRunning(IndexerNotRunningException ex,
                                                           HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * Handles {@code @Valid} bean-validation failures on request bodies.
     *
     * @return 400 Bad Request with a combined field-error message
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                           HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return buildResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    /**
     * Handles bad arguments from the service layer (e.g. unknown chain key).
     *
     * @return 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * Handles illegal state from the service layer (e.g. already running).
     *
     * @return 409 Conflict
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex,
                                                             HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * Handles RPC communication failures.
     *
     * @return 502 Bad Gateway
     */
    @ExceptionHandler(RpcClientService.RpcException.class)
    public ResponseEntity<ErrorResponse> handleRpcException(RpcClientService.RpcException ex,
                                                             HttpServletRequest request) {
        log.error("RPC error during API request to {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_GATEWAY, ex.getMessage(), request);
    }

    /**
     * Catch-all for unexpected exceptions.
     *
     * @return 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex,
                                                        HttpServletRequest request) {
        log.error("Unhandled exception on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred", request);
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String message,
                                                         HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .timestamp(System.currentTimeMillis())
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(status).body(body);
    }
}
