package com.peterstringer.blockchain.indexer.repository;

import com.peterstringer.blockchain.indexer.model.RpcProviderHealth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link RpcProviderHealth} entities.
 *
 * <p>Supports the circuit-breaker pattern used by the RPC client layer.
 * When a provider starts returning errors, its state transitions from
 * {@code CLOSED} → {@code OPEN}. The indexer periodically probes open
 * providers ({@code HALF_OPEN}) and promotes them back to {@code CLOSED}
 * on success.
 *
 * <p>Provider URLs are stored as SHA-256 hashes to avoid persisting
 * potentially sensitive RPC endpoints (which may include API keys).
 */
@Repository
public interface RpcProviderHealthRepository extends JpaRepository<RpcProviderHealth, Long> {

    /**
     * Finds all provider health records for a specific chain.
     *
     * @param chain the chain identifier (e.g. "ethereum")
     * @return all tracked providers for this chain
     */
    List<RpcProviderHealth> findByChain(String chain);

    /**
     * Finds a specific provider by chain and URL hash.
     *
     * @param chain           the chain identifier
     * @param providerUrlHash SHA-256 hash of the provider URL
     * @return the health record if it exists
     */
    Optional<RpcProviderHealth> findByChainAndProviderUrlHash(String chain, String providerUrlHash);

    /**
     * Finds all providers currently in the {@code OPEN} (unhealthy) state
     * across all chains. Used by the health-check scheduler to identify
     * providers that need probing.
     *
     * @return all unhealthy providers
     */
    @Query("SELECT r FROM RpcProviderHealth r WHERE r.state = com.peterstringer.blockchain.indexer.model.RpcProviderHealth.CircuitState.OPEN")
    List<RpcProviderHealth> findAllUnhealthy();

    /**
     * Resets failure counts and transitions all providers for a chain back
     * to {@code CLOSED}. Useful for manual recovery or after an RPC
     * provider outage is resolved.
     *
     * @param chain the chain identifier
     * @return the number of rows updated
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE RpcProviderHealth r
               SET r.failureCount = 0,
                   r.state = com.peterstringer.blockchain.indexer.model.RpcProviderHealth.CircuitState.CLOSED,
                   r.updatedAt = CURRENT_TIMESTAMP
             WHERE r.chain = :chain
            """)
    int resetFailureCountsByChain(@Param("chain") String chain);
}
