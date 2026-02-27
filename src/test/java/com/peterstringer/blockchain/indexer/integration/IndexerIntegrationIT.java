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
 *
 * <p>The indexer uses <strong>reverse backfill</strong>: it starts at
 * the chain head and works backward toward the configured start block.
 * Backfill updates {@code backfillFloorBlock} and {@code totalBlocksIndexed}
 * but does <em>not</em> update {@code lastIndexedBlock} (which is only
 * used by incremental/forward mode).
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

            // Wait for backfill to process blocks (reverse: chainHead→startBlock)
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum")
                                .orElse(null);
                        assertThat(cp).isNotNull();
                        assertThat(cp.getTotalBlocksIndexed()).isGreaterThanOrEqualTo(20L);
                    });

            // Verify checkpoint totals and backfill progress
            IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum").orElseThrow();
            assertThat(cp.getTotalBlocksIndexed()).isGreaterThan(0L);
            assertThat(cp.getTotalTransactionsIndexed()).isGreaterThan(0L);
            assertThat(cp.getBackfillFloorBlock()).isNotNull();
        }

        @Test
        @DisplayName("should resume from existing backfill checkpoint")
        void resumesFromCheckpoint() {
            // Pre-insert checkpoint simulating a partial backfill that reached floor=15
            IndexerCheckpoint existing = new IndexerCheckpoint();
            existing.setChain("ethereum");
            existing.setLastIndexedBlock(-1L);
            existing.setBackfillFloorBlock(15L);
            existing.setTotalBlocksIndexed(10L);
            existing.setTotalTransactionsIndexed(500L);
            existing.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            existing.setLastUpdated(OffsetDateTime.now(ZoneOffset.UTC));
            checkpointRepository.save(existing);

            // Start backfill — should resume from floor=15 and work down to 0
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum")
                                .orElseThrow();
                        // Should have indexed more blocks beyond the initial 10
                        assertThat(cp.getTotalBlocksIndexed()).isGreaterThan(10L);
                    });

            // Transactions accumulated from the pre-existing 500 + new ones
            IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum").orElseThrow();
            assertThat(cp.getTotalTransactionsIndexed()).isGreaterThan(500L);
        }

        @Test
        @DisplayName("should handle multiple chains simultaneously")
        void handlesMultipleChains() {
            indexerService.startAll(BlockIndexerService.IndexMode.BACKFILL);

            // Wait for both chains to make progress
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        IndexerCheckpoint ethCp = checkpointRepository
                                .findByChain("ethereum").orElse(null);
                        IndexerCheckpoint polyCp = checkpointRepository
                                .findByChain("polygon").orElse(null);
                        assertThat(ethCp).isNotNull();
                        assertThat(ethCp.getTotalBlocksIndexed()).isGreaterThan(0L);
                        assertThat(polyCp).isNotNull();
                        assertThat(polyCp.getTotalBlocksIndexed()).isGreaterThan(0L);
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
                        IndexerCheckpoint cp = checkpointRepository
                                .findByChain("ethereum").orElse(null);
                        assertThat(cp).isNotNull();
                        assertThat(cp.getTotalBlocksIndexed()).isGreaterThan(0L);
                    });

            // Stop
            indexerService.stopIndexing("ethereum");

            // Record checkpoint position after stop
            long blocksAfterStop = checkpointRepository.findByChain("ethereum")
                    .orElseThrow().getTotalBlocksIndexed();
            assertThat(blocksAfterStop).isGreaterThan(0L);

            // Resume — should continue from checkpoint
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum")
                                .orElseThrow();
                        assertThat(cp.getTotalBlocksIndexed()).isGreaterThanOrEqualTo(20L);
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

            // Wait for backfill completion.
            // Backfill starts at chainHead (24) and sets high = chainHead - 1 = 23,
            // so it processes blocks 0–23 = 24 blocks total.
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum")
                                .orElse(null);
                        assertThat(cp).isNotNull();
                        assertThat(cp.getTotalBlocksIndexed()).isGreaterThanOrEqualTo(24L);
                    });

            IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum").orElseThrow();
            // 24 blocks total (0..23); chainHead block 24 is not processed by backfill
            assertThat(cp.getTotalBlocksIndexed()).isEqualTo(24L);
            assertThat(cp.getTotalTransactionsIndexed()).isGreaterThan(0L);
            assertThat(cp.getBackfillFloorBlock()).isNotNull();
            assertThat(cp.getBackfillFloorBlock()).isLessThanOrEqualTo(0L);
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
            // startIndexing() sets running state synchronously before spawning
            // the background thread, so we can check immediately after it returns.
            // In demo mode, backfill of 25 blocks completes in <100ms, so
            // Awaitility polling would miss the running window.
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            IndexerStatus status = indexerService.getStatus();
            assertThat(status.isRunning()).isTrue();
            assertThat(status.getMode()).isEqualTo(IndexerStatus.Mode.BACKFILL);
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
                        assertThat(cp.getTotalBlocksIndexed()).isGreaterThanOrEqualTo(20L);
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
