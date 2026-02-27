package com.peterstringer.blockchain.indexer.integration;

import com.peterstringer.blockchain.indexer.model.IndexerCheckpoint;
import com.peterstringer.blockchain.indexer.model.IndexerMetric;
import com.peterstringer.blockchain.indexer.model.RpcProviderHealth;
import com.peterstringer.blockchain.indexer.repository.CheckpointRepository;
import com.peterstringer.blockchain.indexer.repository.MetricsRepository;
import com.peterstringer.blockchain.indexer.repository.RpcProviderHealthRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the database layer against a real PostgreSQL instance.
 *
 * <p>Validates Flyway migrations, repository CRUD operations, atomic updates,
 * unique constraints, and PostgreSQL-specific data type handling (e.g.,
 * {@code TIMESTAMP WITH TIME ZONE}).
 */
@DisplayName("Database Integration")
class DatabaseIntegrationIT extends AbstractIntegrationTest {

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private MetricsRepository metricsRepository;

    @Autowired
    private RpcProviderHealthRepository rpcProviderHealthRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        rpcProviderHealthRepository.deleteAll();
        metricsRepository.deleteAll();
        checkpointRepository.deleteAll();
    }

    // =========================================================================
    // Flyway migration tests
    // =========================================================================

    @Nested
    @DisplayName("Flyway Migrations")
    class FlywayMigrationTests {

        @Test
        @DisplayName("should create indexer_checkpoints table with correct columns")
        void checkpointsTableExists() {
            List<String> columns = jdbcTemplate.queryForList(
                    """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'indexer_checkpoints'
                    ORDER BY ordinal_position
                    """,
                    String.class);

            assertThat(columns).containsExactly(
                    "id", "chain", "last_indexed_block",
                    "total_blocks_indexed", "total_transactions_indexed",
                    "last_updated", "created_at", "backfill_floor_block");
        }

        @Test
        @DisplayName("should create indexer_metrics table with correct columns")
        void metricsTableExists() {
            List<String> columns = jdbcTemplate.queryForList(
                    """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'indexer_metrics'
                    ORDER BY ordinal_position
                    """,
                    String.class);

            assertThat(columns).containsExactly(
                    "id", "chain", "metric_name", "metric_value", "recorded_at");
        }

        @Test
        @DisplayName("should create rpc_provider_health table with correct columns")
        void rpcProviderHealthTableExists() {
            List<String> columns = jdbcTemplate.queryForList(
                    """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'rpc_provider_health'
                    ORDER BY ordinal_position
                    """,
                    String.class);

            assertThat(columns).containsExactly(
                    "id", "chain", "provider_url_hash", "state",
                    "success_count", "failure_count",
                    "last_failure_time", "last_success_time", "updated_at");
        }

        @Test
        @DisplayName("should have index on checkpoints chain column")
        void checkpointsChainIndex() {
            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE tablename = 'indexer_checkpoints'
                      AND indexname = 'idx_checkpoints_chain'
                    """,
                    Integer.class);

            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("should have composite index on metrics table")
        void metricsCompositeIndex() {
            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM pg_indexes
                    WHERE tablename = 'indexer_metrics'
                      AND indexname = 'idx_metrics_chain_name_time'
                    """,
                    Integer.class);

            assertThat(count).isEqualTo(1);
        }
    }

    // =========================================================================
    // Checkpoint repository tests
    // =========================================================================

    @Nested
    @DisplayName("Checkpoint Repository")
    class CheckpointRepositoryTests {

        @Test
        @DisplayName("should save and find checkpoint by chain")
        void saveAndFindByChain() {
            IndexerCheckpoint cp = createCheckpoint("ethereum", 1000L);
            checkpointRepository.save(cp);

            Optional<IndexerCheckpoint> found = checkpointRepository.findByChain("ethereum");

            assertThat(found).isPresent();
            assertThat(found.get().getChain()).isEqualTo("ethereum");
            assertThat(found.get().getLastIndexedBlock()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("should return empty for unknown chain")
        void returnsEmptyForUnknownChain() {
            Optional<IndexerCheckpoint> found = checkpointRepository.findByChain("unknown");
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("should find all ordered by last indexed block desc")
        void findAllOrdered() {
            checkpointRepository.save(createCheckpoint("ethereum", 500L));
            checkpointRepository.save(createCheckpoint("polygon", 2000L));
            checkpointRepository.save(createCheckpoint("arbitrum", 100L));

            List<IndexerCheckpoint> all = checkpointRepository.findAllOrderByLastIndexedBlockDesc();

            assertThat(all).hasSize(3);
            assertThat(all.get(0).getChain()).isEqualTo("polygon");
            assertThat(all.get(1).getChain()).isEqualTo("ethereum");
            assertThat(all.get(2).getChain()).isEqualTo("arbitrum");
        }

        @Test
        @DisplayName("should atomically increment totals via updateCheckpoint")
        @Transactional
        void atomicUpdateCheckpoint() {
            IndexerCheckpoint cp = createCheckpoint("ethereum", 100L);
            cp.setTotalBlocksIndexed(0L);
            cp.setTotalTransactionsIndexed(0L);
            checkpointRepository.saveAndFlush(cp);

            // First batch: 5 blocks, 50 transactions
            int updated = checkpointRepository.updateCheckpoint(
                    "ethereum", 105L, 5L, 50L, OffsetDateTime.now(ZoneOffset.UTC));
            assertThat(updated).isEqualTo(1);

            // Second batch: 3 blocks, 30 transactions
            checkpointRepository.updateCheckpoint(
                    "ethereum", 108L, 3L, 30L, OffsetDateTime.now(ZoneOffset.UTC));

            // Flush pending SQL then clear L1 cache so findByChain re-reads from DB
            checkpointRepository.flush();
            entityManager.clear();
            IndexerCheckpoint result = checkpointRepository.findByChain("ethereum").orElseThrow();
            assertThat(result.getLastIndexedBlock()).isEqualTo(108L);
            assertThat(result.getTotalBlocksIndexed()).isEqualTo(8L);
            assertThat(result.getTotalTransactionsIndexed()).isEqualTo(80L);
        }

        @Test
        @DisplayName("should enforce unique chain constraint")
        void enforcesUniqueChain() {
            checkpointRepository.save(createCheckpoint("ethereum", 100L));

            assertThatThrownBy(() -> {
                checkpointRepository.saveAndFlush(createCheckpoint("ethereum", 200L));
            }).isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("should return 0 when updating non-existent chain")
        @Transactional
        void updateReturnsZeroForMissingChain() {
            int updated = checkpointRepository.updateCheckpoint(
                    "nonexistent", 100L, 5L, 50L, OffsetDateTime.now(ZoneOffset.UTC));

            assertThat(updated).isEqualTo(0);
        }
    }

    // =========================================================================
    // Metrics repository tests
    // =========================================================================

    @Nested
    @DisplayName("Metrics Repository")
    class MetricsRepositoryTests {

        @Test
        @DisplayName("should save and query by chain, metric name, and time range")
        void saveAndQueryByTimeRange() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            metricsRepository.save(createMetric("ethereum", "blocks_per_second", 42.5, now.minusMinutes(30)));
            metricsRepository.save(createMetric("ethereum", "blocks_per_second", 55.0, now.minusMinutes(15)));
            metricsRepository.save(createMetric("ethereum", "blocks_per_second", 38.0, now.minusMinutes(5)));

            List<IndexerMetric> results = metricsRepository
                    .findByChainAndMetricNameAndRecordedAtAfterOrderByRecordedAtAsc(
                            "ethereum", "blocks_per_second", now.minusMinutes(20));

            assertThat(results).hasSize(2);
            assertThat(results.get(0).getMetricValue()).isEqualTo(55.0);
            assertThat(results.get(1).getMetricValue()).isEqualTo(38.0);
        }

        @Test
        @DisplayName("should compute average metric value over time window")
        void computesAverage() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            metricsRepository.save(createMetric("ethereum", "bps", 10.0, now.minusMinutes(10)));
            metricsRepository.save(createMetric("ethereum", "bps", 20.0, now.minusMinutes(5)));
            metricsRepository.save(createMetric("ethereum", "bps", 30.0, now));

            Double avg = metricsRepository.averageMetricValue(
                    "ethereum", "bps", now.minusMinutes(15), now.plusMinutes(1));

            assertThat(avg).isEqualTo(20.0);
        }

        @Test
        @DisplayName("should compute max metric value over time window")
        void computesMax() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            metricsRepository.save(createMetric("ethereum", "bps", 10.0, now.minusMinutes(10)));
            metricsRepository.save(createMetric("ethereum", "bps", 99.5, now.minusMinutes(5)));
            metricsRepository.save(createMetric("ethereum", "bps", 30.0, now));

            Double max = metricsRepository.maxMetricValue(
                    "ethereum", "bps", now.minusMinutes(15), now.plusMinutes(1));

            assertThat(max).isEqualTo(99.5);
        }

        @Test
        @DisplayName("should return null for empty time range")
        void returnsNullForEmptyRange() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            Double avg = metricsRepository.averageMetricValue(
                    "ethereum", "bps", now.minusMinutes(1), now);

            assertThat(avg).isNull();
        }

        @Test
        @DisplayName("should filter metrics by chain correctly")
        void filtersByChain() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            metricsRepository.save(createMetric("ethereum", "bps", 42.0, now));
            metricsRepository.save(createMetric("polygon", "bps", 99.0, now));

            List<IndexerMetric> ethMetrics = metricsRepository
                    .findByChainAndMetricNameAndRecordedAtAfterOrderByRecordedAtAsc(
                            "ethereum", "bps", now.minusMinutes(1));

            assertThat(ethMetrics).hasSize(1);
            assertThat(ethMetrics.get(0).getMetricValue()).isEqualTo(42.0);
        }
    }

    // =========================================================================
    // RPC provider health repository tests
    // =========================================================================

    @Nested
    @DisplayName("RPC Provider Health Repository")
    class RpcProviderHealthRepositoryTests {

        @Test
        @DisplayName("should save and find by chain")
        void saveAndFindByChain() {
            rpcProviderHealthRepository.save(
                    createProviderHealth("ethereum", "hash1", RpcProviderHealth.CircuitState.CLOSED));
            rpcProviderHealthRepository.save(
                    createProviderHealth("ethereum", "hash2", RpcProviderHealth.CircuitState.OPEN));

            List<RpcProviderHealth> providers = rpcProviderHealthRepository.findByChain("ethereum");

            assertThat(providers).hasSize(2);
        }

        @Test
        @DisplayName("should find by chain and provider URL hash")
        void findByChainAndHash() {
            rpcProviderHealthRepository.save(
                    createProviderHealth("ethereum", "abc123", RpcProviderHealth.CircuitState.CLOSED));

            Optional<RpcProviderHealth> found = rpcProviderHealthRepository
                    .findByChainAndProviderUrlHash("ethereum", "abc123");

            assertThat(found).isPresent();
            assertThat(found.get().getProviderUrlHash()).isEqualTo("abc123");
        }

        @Test
        @DisplayName("should find all unhealthy providers (OPEN state)")
        void findAllUnhealthy() {
            rpcProviderHealthRepository.save(
                    createProviderHealth("ethereum", "h1", RpcProviderHealth.CircuitState.CLOSED));
            rpcProviderHealthRepository.save(
                    createProviderHealth("ethereum", "h2", RpcProviderHealth.CircuitState.OPEN));
            rpcProviderHealthRepository.save(
                    createProviderHealth("polygon", "h3", RpcProviderHealth.CircuitState.OPEN));

            List<RpcProviderHealth> unhealthy = rpcProviderHealthRepository.findAllUnhealthy();

            assertThat(unhealthy).hasSize(2);
            assertThat(unhealthy).allMatch(p -> p.getState() == RpcProviderHealth.CircuitState.OPEN);
        }

        @Test
        @DisplayName("should reset failure counts by chain")
        @Transactional
        void resetFailureCounts() {
            RpcProviderHealth provider = createProviderHealth("ethereum", "h1",
                    RpcProviderHealth.CircuitState.OPEN);
            provider.setFailureCount(10L);
            rpcProviderHealthRepository.saveAndFlush(provider);

            int updated = rpcProviderHealthRepository.resetFailureCountsByChain("ethereum");

            assertThat(updated).isEqualTo(1);

            rpcProviderHealthRepository.flush();
            entityManager.clear();
            RpcProviderHealth result = rpcProviderHealthRepository
                    .findByChainAndProviderUrlHash("ethereum", "h1").orElseThrow();
            assertThat(result.getFailureCount()).isEqualTo(0L);
            assertThat(result.getState()).isEqualTo(RpcProviderHealth.CircuitState.CLOSED);
        }

        @Test
        @DisplayName("should not reset other chains when resetting one")
        @Transactional
        void resetDoesNotAffectOtherChains() {
            RpcProviderHealth ethProvider = createProviderHealth("ethereum", "h1",
                    RpcProviderHealth.CircuitState.OPEN);
            ethProvider.setFailureCount(5L);
            rpcProviderHealthRepository.saveAndFlush(ethProvider);

            RpcProviderHealth polyProvider = createProviderHealth("polygon", "h2",
                    RpcProviderHealth.CircuitState.OPEN);
            polyProvider.setFailureCount(8L);
            rpcProviderHealthRepository.saveAndFlush(polyProvider);

            rpcProviderHealthRepository.resetFailureCountsByChain("ethereum");

            rpcProviderHealthRepository.flush();
            entityManager.clear();
            RpcProviderHealth polyResult = rpcProviderHealthRepository
                    .findByChainAndProviderUrlHash("polygon", "h2").orElseThrow();
            assertThat(polyResult.getFailureCount()).isEqualTo(8L);
            assertThat(polyResult.getState()).isEqualTo(RpcProviderHealth.CircuitState.OPEN);
        }

        @Test
        @DisplayName("should enforce unique constraint on chain + provider_url_hash")
        void enforcesUniqueConstraint() {
            rpcProviderHealthRepository.save(
                    createProviderHealth("ethereum", "same_hash", RpcProviderHealth.CircuitState.CLOSED));

            assertThatThrownBy(() -> {
                rpcProviderHealthRepository.saveAndFlush(
                        createProviderHealth("ethereum", "same_hash", RpcProviderHealth.CircuitState.OPEN));
            }).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // =========================================================================
    // Data integrity tests
    // =========================================================================

    @Nested
    @DisplayName("Data Integrity")
    class DataIntegrityTests {

        @Test
        @DisplayName("should persist OffsetDateTime with timezone correctly")
        void persistsTimezoneCorrectly() {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            IndexerCheckpoint cp = createCheckpoint("ethereum", 100L);
            cp.setCreatedAt(now);
            cp.setLastUpdated(now);
            checkpointRepository.save(cp);

            IndexerCheckpoint loaded = checkpointRepository.findByChain("ethereum").orElseThrow();

            // PostgreSQL TIMESTAMP WITH TIME ZONE stores as UTC
            assertThat(loaded.getCreatedAt()).isNotNull();
            assertThat(loaded.getLastUpdated()).isNotNull();
            assertThat(loaded.getCreatedAt().toInstant())
                    .isCloseTo(now.toInstant(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("should handle large block numbers (near Long.MAX_VALUE)")
        void handlesLargeBlockNumbers() {
            long largeBlock = Long.MAX_VALUE - 1;
            IndexerCheckpoint cp = createCheckpoint("ethereum", largeBlock);
            checkpointRepository.save(cp);

            IndexerCheckpoint loaded = checkpointRepository.findByChain("ethereum").orElseThrow();
            assertThat(loaded.getLastIndexedBlock()).isEqualTo(largeBlock);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static IndexerCheckpoint createCheckpoint(String chain, long lastBlock) {
        IndexerCheckpoint cp = new IndexerCheckpoint();
        cp.setChain(chain);
        cp.setLastIndexedBlock(lastBlock);
        cp.setTotalBlocksIndexed(0L);
        cp.setTotalTransactionsIndexed(0L);
        cp.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        cp.setLastUpdated(OffsetDateTime.now(ZoneOffset.UTC));
        return cp;
    }

    private static IndexerMetric createMetric(String chain, String name, double value,
                                               OffsetDateTime recordedAt) {
        IndexerMetric metric = new IndexerMetric();
        metric.setChain(chain);
        metric.setMetricName(name);
        metric.setMetricValue(value);
        metric.setRecordedAt(recordedAt);
        return metric;
    }

    private static RpcProviderHealth createProviderHealth(String chain, String urlHash,
                                                          RpcProviderHealth.CircuitState state) {
        RpcProviderHealth health = new RpcProviderHealth();
        health.setChain(chain);
        health.setProviderUrlHash(urlHash);
        health.setState(state);
        health.setSuccessCount(0L);
        health.setFailureCount(0L);
        health.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return health;
    }
}
