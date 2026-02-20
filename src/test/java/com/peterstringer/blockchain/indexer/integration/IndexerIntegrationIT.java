package com.peterstringer.blockchain.indexer.integration;

import com.peterstringer.blockchain.indexer.model.IndexerCheckpoint;
import com.peterstringer.blockchain.indexer.model.IndexerStatus;
import com.peterstringer.blockchain.indexer.repository.CheckpointRepository;
import com.peterstringer.blockchain.indexer.service.BlockIndexerService;
import com.peterstringer.blockchain.indexer.service.ParquetWriterService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the indexing pipeline.
 *
 * <p>Validates the full flow: start indexing in demo mode, generate
 * synthetic blocks, write to Parquet buffers, persist checkpoints
 * to PostgreSQL, and track status transitions.
 */
@DisplayName("Indexer Integration")
class IndexerIntegrationIT extends AbstractIntegrationTest {

    @Autowired
    private BlockIndexerService indexerService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private ParquetWriterService parquetWriterService;

    @AfterEach
    void tearDown() {
        indexerService.stopAll();
        checkpointRepository.deleteAll();
    }

    // =========================================================================
    // Backfill end-to-end
    // =========================================================================

    @Nested
    @DisplayName("Backfill End-to-End")
    class BackfillEndToEndTests {

        @Test
        @DisplayName("should index all blocks in demo mode backfill")
        void indexesAllBlocksInDemoMode() {
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            // Wait for backfill to complete (end-block=20, batch-size=5)
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum")
                                .orElse(null);
                        assertThat(cp).isNotNull();
                        assertThat(cp.getLastIndexedBlock()).isGreaterThanOrEqualTo(20L);
                    });

            // Verify checkpoint totals
            IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum").orElseThrow();
            assertThat(cp.getTotalBlocksIndexed()).isGreaterThan(0L);
            assertThat(cp.getTotalTransactionsIndexed()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("should resume from existing checkpoint")
        void resumesFromCheckpoint() {
            // Pre-insert checkpoint at block 10
            IndexerCheckpoint existing = new IndexerCheckpoint();
            existing.setChain("ethereum");
            existing.setLastIndexedBlock(10L);
            existing.setTotalBlocksIndexed(11L);
            existing.setTotalTransactionsIndexed(500L);
            existing.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            existing.setLastUpdated(OffsetDateTime.now(ZoneOffset.UTC));
            checkpointRepository.save(existing);

            // Start backfill — should resume from block 11
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum")
                                .orElseThrow();
                        assertThat(cp.getLastIndexedBlock()).isGreaterThanOrEqualTo(20L);
                    });

            // Should have indexed blocks 11-20 (10 more blocks)
            IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum").orElseThrow();
            assertThat(cp.getTotalBlocksIndexed()).isGreaterThanOrEqualTo(21L);
            // Transactions accumulated from the pre-existing 500 + new ones
            assertThat(cp.getTotalTransactionsIndexed()).isGreaterThan(500L);
        }

        @Test
        @DisplayName("should handle multiple chains simultaneously")
        void handlesMultipleChains() {
            indexerService.startAll(BlockIndexerService.IndexMode.BACKFILL);

            // Wait for both chains to complete
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        assertThat(checkpointRepository.findByChain("ethereum"))
                                .isPresent()
                                .get()
                                .extracting(IndexerCheckpoint::getLastIndexedBlock)
                                .satisfies(block -> assertThat((Long) block).isGreaterThanOrEqualTo(20L));
                        assertThat(checkpointRepository.findByChain("polygon"))
                                .isPresent()
                                .get()
                                .extracting(IndexerCheckpoint::getLastIndexedBlock)
                                .satisfies(block -> assertThat((Long) block).isGreaterThanOrEqualTo(10L));
                    });
        }
    }

    // =========================================================================
    // Stop and resume
    // =========================================================================

    @Nested
    @DisplayName("Stop and Resume")
    class StopAndResumeTests {

        @Test
        @DisplayName("should stop gracefully and resume from checkpoint")
        void stopAndResume() {
            // Start ethereum backfill
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            // Wait until some blocks are indexed
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        assertThat(checkpointRepository.findByChain("ethereum")).isPresent();
                    });

            // Stop
            indexerService.stopIndexing("ethereum");

            // Record checkpoint position
            long checkpointAfterStop = checkpointRepository.findByChain("ethereum")
                    .orElseThrow().getLastIndexedBlock();
            assertThat(checkpointAfterStop).isGreaterThanOrEqualTo(0L);

            // Resume — should start from checkpoint
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum")
                                .orElseThrow();
                        assertThat(cp.getLastIndexedBlock()).isGreaterThanOrEqualTo(20L);
                    });
        }
    }

    // =========================================================================
    // Checkpoint persistence
    // =========================================================================

    @Nested
    @DisplayName("Checkpoint Persistence")
    class CheckpointPersistenceTests {

        @Test
        @DisplayName("should persist checkpoint with correct totals to PostgreSQL")
        void persistsCorrectTotals() {
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            // Wait for completion
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum")
                                .orElse(null);
                        assertThat(cp).isNotNull();
                        assertThat(cp.getLastIndexedBlock()).isGreaterThanOrEqualTo(20L);
                    });

            IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum").orElseThrow();
            // blocks 0..20 = 21 blocks total
            assertThat(cp.getTotalBlocksIndexed()).isEqualTo(21L);
            assertThat(cp.getTotalTransactionsIndexed()).isGreaterThan(0L);
            assertThat(cp.getLastUpdated()).isNotNull();
            assertThat(cp.getCreatedAt()).isNotNull();
        }
    }

    // =========================================================================
    // Status tracking
    // =========================================================================

    @Nested
    @DisplayName("Status Tracking")
    class StatusTrackingTests {

        @Test
        @DisplayName("should report running status during indexing")
        void reportsRunningDuringIndexing() {
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            // Verify status shows running
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> {
                        IndexerStatus status = indexerService.getStatus();
                        assertThat(status.isRunning()).isTrue();
                        assertThat(status.getMode()).isEqualTo(IndexerStatus.Mode.BACKFILL);
                    });
        }

        @Test
        @DisplayName("should report stopped after backfill completion")
        void reportsStoppedAfterCompletion() {
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            // Wait for backfill to complete
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum")
                                .orElse(null);
                        assertThat(cp).isNotNull();
                        assertThat(cp.getLastIndexedBlock()).isGreaterThanOrEqualTo(20L);
                    });

            // Wait for the indexing thread to exit
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        IndexerStatus status = indexerService.getStatus();
                        assertThat(status.isRunning()).isFalse();
                    });
        }

        @Test
        @DisplayName("should track Parquet writer statistics during indexing")
        void tracksParquetStatistics() {
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        Map<String, Object> stats = parquetWriterService.getStatistics();
                        long buffered = (long) stats.get("bufferedBlocks");
                        long written = (long) stats.get("totalBlocksWritten");
                        // Blocks are either buffered or written
                        assertThat(buffered + written).isGreaterThan(0L);
                    });
        }
    }
}
