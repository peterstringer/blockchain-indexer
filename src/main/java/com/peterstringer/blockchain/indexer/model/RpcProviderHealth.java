package com.peterstringer.blockchain.indexer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * JPA entity mapping the {@code rpc_provider_health} table.
 *
 * <p>Implements a circuit-breaker pattern for RPC provider health tracking.
 * Each provider URL (hashed for privacy) is tracked independently per chain,
 * allowing the indexer to route requests away from unhealthy providers and
 * periodically probe them for recovery.
 *
 * <p>State transitions:
 * <ul>
 *   <li>{@code CLOSED} — healthy; requests flow normally.</li>
 *   <li>{@code OPEN} — unhealthy; requests are redirected to other providers.</li>
 *   <li>{@code HALF_OPEN} — probing; a single test request determines recovery.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rpc_provider_health")
public class RpcProviderHealth {

    /**
     * Circuit-breaker states for RPC provider health.
     */
    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain", nullable = false, length = 50)
    private String chain;

    @Column(name = "provider_url_hash", nullable = false, length = 64)
    private String providerUrlHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private CircuitState state = CircuitState.CLOSED;

    @Column(name = "success_count")
    private Long successCount = 0L;

    @Column(name = "failure_count")
    private Long failureCount = 0L;

    @Column(name = "last_failure_time")
    private OffsetDateTime lastFailureTime;

    @Column(name = "last_success_time")
    private OffsetDateTime lastSuccessTime;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
