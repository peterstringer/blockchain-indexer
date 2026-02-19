package com.peterstringer.blockchain.indexer.model.ws;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Circuit breaker state for a single RPC provider, embedded in
 * {@link RpcHealthMessage}.
 *
 * <p>The {@code urlHash} is a SHA-256 hash of the provider URL,
 * avoiding exposure of RPC credentials in WebSocket messages while
 * still allowing correlation with the provider health database.
 *
 * <p>States:
 * <ul>
 *   <li>{@code CLOSED} — healthy, requests flow normally.</li>
 *   <li>{@code OPEN} — tripped, requests are routed elsewhere.</li>
 *   <li>{@code HALF_OPEN} — probing with a single test request.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderState {

    /** SHA-256 hash of the provider URL (first 16 hex chars). */
    private String urlHash;

    /** Circuit breaker state: CLOSED, OPEN, or HALF_OPEN. */
    private String state;

    /** Cumulative successful RPC calls. */
    private Long successCount;

    /** Cumulative failed RPC calls. */
    private Long failureCount;
}
