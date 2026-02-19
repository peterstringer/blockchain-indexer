package com.peterstringer.blockchain.indexer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard error response body returned by the global exception handler.
 *
 * <p>Example JSON:
 * <pre>
 * {
 *   "status": 404,
 *   "error": "Not Found",
 *   "message": "Chain not found: avalanche",
 *   "timestamp": 1708300800000,
 *   "path": "/api/indexer/status/avalanche"
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    /** HTTP status code. */
    private int status;

    /** HTTP status reason phrase (e.g. "Not Found", "Conflict"). */
    private String error;

    /** Human-readable description of the problem. */
    private String message;

    /** Epoch millis when the error occurred. */
    private long timestamp;

    /** The request URI that triggered the error. */
    private String path;
}
