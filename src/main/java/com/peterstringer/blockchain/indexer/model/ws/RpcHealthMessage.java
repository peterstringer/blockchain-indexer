package com.peterstringer.blockchain.indexer.model.ws;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * WebSocket message sent on {@code /topic/indexer/{chain}/rpc-health} when
 * RPC provider health changes are detected.
 *
 * <p>Carries a snapshot of all configured providers for a chain, including
 * circuit breaker state and cumulative success/failure counts. Useful for
 * dashboard health panels and alerting.
 *
 * <p>Example JSON payload:
 * <pre>
 * {
 *   "chain":            "ethereum",
 *   "providersTotal":   3,
 *   "providersHealthy": 2,
 *   "providerStates": [
 *     {
 *       "urlHash":      "a1b2c3d4",
 *       "state":        "CLOSED",
 *       "successCount": 15200,
 *       "failureCount": 3
 *     },
 *     {
 *       "urlHash":      "e5f6g7h8",
 *       "state":        "OPEN",
 *       "successCount": 8000,
 *       "failureCount": 47
 *     }
 *   ],
 *   "timestamp": 1708300800000
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcHealthMessage {

    /** Chain key (e.g. "ethereum"). */
    private String chain;

    /** Total number of configured RPC providers for this chain. */
    private Integer providersTotal;

    /** Number of providers currently in CLOSED (healthy) state. */
    private Integer providersHealthy;

    /** Per-provider circuit breaker state. */
    private List<ProviderState> providerStates;

    /** Epoch millis when this snapshot was taken. */
    private Long timestamp;
}
