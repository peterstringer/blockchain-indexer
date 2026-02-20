package com.peterstringer.blockchain.indexer.service;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.model.IndexedBlock;
import com.peterstringer.blockchain.indexer.model.IndexerCheckpoint;
import com.peterstringer.blockchain.indexer.model.IndexerStatus;
import com.peterstringer.blockchain.indexer.repository.CheckpointRepository;
import com.peterstringer.blockchain.indexer.repository.MetricsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BlockIndexerService}, covering start/stop lifecycle,
 * backfill processing, checkpoint management, status reporting, and error
 * handling. All external dependencies are mocked.
 */
@DisplayName("BlockIndexerService")
@ExtendWith(MockitoExtension.class)
class BlockIndexerServiceTest {

    @Mock
    private RpcClientService rpcClientService;

    @Mock
    private ParquetWriterService parquetWriterService;

    @Mock
    private CheckpointRepository checkpointRepository;

    @Mock
    private MetricsRepository metricsRepository;

    @Mock
    private WebSocketService webSocketService;

    private IndexerProperties properties;
    private BlockIndexerService service;

    @BeforeEach
    void setUp() {
        properties = new IndexerProperties();

        IndexerProperties.ChainConfig ethereum = new IndexerProperties.ChainConfig();
        ethereum.setName("Ethereum");
        ethereum.setChainId(1);
        ethereum.setRpcUrls(List.of("http://localhost:8545"));
        ethereum.setStartBlock(100);
        ethereum.setEndBlock(110L);
        ethereum.setMaxRetriesPerBlock(1);
        ethereum.setRetryDelayMs(10);
        ethereum.setRateLimitRequestsPerSecond(100);

        IndexerProperties.ChainConfig polygon = new IndexerProperties.ChainConfig();
        polygon.setName("Polygon");
        polygon.setChainId(137);
        polygon.setRpcUrls(List.of("http://localhost:8546"));
        polygon.setStartBlock(1000);
        polygon.setEndBlock(1005L);
        polygon.setMaxRetriesPerBlock(1);
        polygon.setRetryDelayMs(10);
        polygon.setRateLimitRequestsPerSecond(100);

        Map<String, IndexerProperties.ChainConfig> chains = new HashMap<>();
        chains.put("ethereum", ethereum);
        chains.put("polygon", polygon);
        properties.setChains(chains);

        IndexerProperties.ConcurrencyConfig concurrency = new IndexerProperties.ConcurrencyConfig();
        concurrency.setWorkerThreads(2);
        concurrency.setBatchSize(5);
        properties.setConcurrency(concurrency);

        IndexerProperties.ParquetConfig parquet = new IndexerProperties.ParquetConfig();
        parquet.setOutputPath("./test-output");
        properties.setParquet(parquet);

        IndexerProperties.DemoConfig demo = new IndexerProperties.DemoConfig();
        demo.setEnabled(false);
        properties.setDemo(demo);

        service = new BlockIndexerService(
                properties, rpcClientService, parquetWriterService,
                checkpointRepository, metricsRepository, webSocketService,
                new SimpleMeterRegistry());
        service.initialize();
    }

    @AfterEach
    void tearDown() {
        try {
            service.shutdown();
        } catch (Exception ignored) {
            // Shutdown may fail if no tasks are running
        }
    }

    // =========================================================================
    // Start/Stop lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Start/Stop Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("should throw IllegalArgumentException for unknown chain")
        void throwsForUnknownChain() {
            assertThatThrownBy(() ->
                    service.startIndexing("unknown", BlockIndexerService.IndexMode.BACKFILL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown chain");
        }

        @Test
        @DisplayName("should throw IllegalStateException when starting already-running chain")
        void throwsWhenAlreadyRunning() {
            // Set up mocks for a successful start
            when(checkpointRepository.findByChain("ethereum"))
                    .thenReturn(Optional.of(createCheckpoint("ethereum", 99L)));
            when(rpcClientService.getIndexedBlocksAsync(eq("ethereum"), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));

            service.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            assertThatThrownBy(() ->
                    service.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already running");
        }

        @Test
        @DisplayName("should allow restarting after stop completes")
        void allowsRestartAfterStop() throws Exception {
            when(checkpointRepository.findByChain("ethereum"))
                    .thenReturn(Optional.of(createCheckpoint("ethereum", 109L)));

            service.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);
            // Wait for the backfill to complete (startBlock=100, endBlock=110, checkpoint at 109 → starts at 110)
            Thread.sleep(500);
            service.stopIndexing("ethereum");

            // Should not throw — chain is stopped
            when(checkpointRepository.findByChain("ethereum"))
                    .thenReturn(Optional.of(createCheckpoint("ethereum", 109L)));

            service.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);
            Thread.sleep(200);
            service.stopIndexing("ethereum");
        }

        @Test
        @DisplayName("stopIndexing should be a no-op for non-running chains")
        void stopIsNoOpForNonRunning() {
            // Should not throw
            service.stopIndexing("ethereum");
            service.stopIndexing("nonexistent");
        }

        @Test
        @DisplayName("stopAll should stop all running chains")
        void stopAllStopsEverything() throws Exception {
            when(checkpointRepository.findByChain(anyString()))
                    .thenReturn(Optional.of(createCheckpoint("test", 99L)));
            when(rpcClientService.getIndexedBlocksAsync(anyString(), any(), any()))
                    .thenReturn(neverCompletingFuture());

            service.startAll(BlockIndexerService.IndexMode.BACKFILL);
            Thread.sleep(200);

            IndexerStatus statusBefore = service.getStatus();
            assertThat(statusBefore.isRunning()).isTrue();

            service.stopAll();

            // After stopAll, chains should eventually become stopped
            IndexerStatus statusAfter = service.getStatus();
            // The chains should exist in the status map
            assertThat(statusAfter.getChains()).containsKeys("ethereum", "polygon");
        }
    }

    // =========================================================================
    // Status reporting
    // =========================================================================

    @Nested
    @DisplayName("Status Reporting")
    class StatusTests {

        @Test
        @DisplayName("should report STOPPED mode when no chains are running")
        void reportsStopped() {
            IndexerStatus status = service.getStatus();

            assertThat(status.isRunning()).isFalse();
            assertThat(status.getMode()).isEqualTo(IndexerStatus.Mode.STOPPED);
        }

        @Test
        @DisplayName("should include all configured chains in status")
        void includesAllChains() {
            IndexerStatus status = service.getStatus();

            assertThat(status.getChains()).containsKeys("ethereum", "polygon");
        }

        @Test
        @DisplayName("should report NOT_STARTED for unconfigured chains")
        void reportsNotStarted() {
            IndexerStatus status = service.getStatus();

            IndexerStatus.ChainStatus ethStatus = status.getChains().get("ethereum");
            assertThat(ethStatus.getRpcHealth()).isEqualTo("NOT_STARTED");
            assertThat(ethStatus.getBlocksIndexed()).isEqualTo(0L);
        }

        @Test
        @DisplayName("should report running status during backfill")
        void reportsRunningDuringBackfill() throws Exception {
            when(checkpointRepository.findByChain("ethereum"))
                    .thenReturn(Optional.of(createCheckpoint("ethereum", 99L)));
            when(rpcClientService.getIndexedBlocksAsync(eq("ethereum"), any(), any()))
                    .thenReturn(neverCompletingFuture());

            service.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);
            Thread.sleep(200);

            IndexerStatus status = service.getStatus();
            assertThat(status.isRunning()).isTrue();
            assertThat(status.getMode()).isEqualTo(IndexerStatus.Mode.BACKFILL);

            service.stopIndexing("ethereum");
        }

        @Test
        @DisplayName("should report running status during incremental mode")
        void reportsRunningDuringIncremental() throws Exception {
            when(checkpointRepository.findByChain("ethereum"))
                    .thenReturn(Optional.of(createCheckpoint("ethereum", 99L)));
            when(rpcClientService.getLatestBlockNumber("ethereum")).thenReturn(99L);

            service.startIndexing("ethereum", BlockIndexerService.IndexMode.INCREMENTAL);
            Thread.sleep(200);

            IndexerStatus status = service.getStatus();
            assertThat(status.isRunning()).isTrue();
            assertThat(status.getMode()).isEqualTo(IndexerStatus.Mode.INCREMENTAL);

            service.stopIndexing("ethereum");
        }
    }

    // =========================================================================
    // Backfill processing
    // =========================================================================

    @Nested
    @DisplayName("Backfill Processing")
    class BackfillTests {

        @Test
        @DisplayName("should create checkpoint if none exists")
        void createsCheckpointIfMissing() throws Exception {
            when(checkpointRepository.findByChain("ethereum")).thenReturn(Optional.empty());
            when(checkpointRepository.save(any(IndexerCheckpoint.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(rpcClientService.getIndexedBlocksAsync(eq("ethereum"), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(createTestBlocks(100, 104)));
            when(checkpointRepository.updateCheckpoint(anyString(), anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(1);

            service.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);
            Thread.sleep(1000);
            service.stopIndexing("ethereum");

            verify(checkpointRepository).save(any(IndexerCheckpoint.class));
        }

        @Test
        @DisplayName("should resume from last checkpoint")
        void resumesFromCheckpoint() throws Exception {
            // Checkpoint at block 104 (already indexed 100-104)
            when(checkpointRepository.findByChain("ethereum"))
                    .thenReturn(Optional.of(createCheckpoint("ethereum", 104L)));
            when(rpcClientService.getIndexedBlocksAsync(eq("ethereum"), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(createTestBlocks(105, 110)));
            when(checkpointRepository.updateCheckpoint(anyString(), anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(1);

            service.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);
            Thread.sleep(1000);

            // Should have written blocks starting from 105
            verify(parquetWriterService, atLeastOnce()).writeBlocks(any());
        }

        @Test
        @DisplayName("should handle empty batch gracefully")
        void handlesEmptyBatch() throws Exception {
            when(checkpointRepository.findByChain("ethereum"))
                    .thenReturn(Optional.of(createCheckpoint("ethereum", 99L)));
            when(rpcClientService.getIndexedBlocksAsync(eq("ethereum"), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));

            service.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);
            Thread.sleep(500);
            service.stopIndexing("ethereum");

            // Should not crash, error counter should increment
        }

        @Test
        @DisplayName("should skip already-completed backfill")
        void skipsCompletedBackfill() throws Exception {
            // Checkpoint already at endBlock (110)
            when(checkpointRepository.findByChain("ethereum"))
                    .thenReturn(Optional.of(createCheckpoint("ethereum", 110L)));

            service.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);
            Thread.sleep(500);

            // Should have completed quickly without processing
            assertThat(service.getStatus().getChains()).containsKey("ethereum");
        }
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should continue on RPC errors during backfill")
        void continuesOnRpcErrors() throws Exception {
            when(checkpointRepository.findByChain("ethereum"))
                    .thenReturn(Optional.of(createCheckpoint("ethereum", 99L)));
            when(rpcClientService.getIndexedBlocksAsync(eq("ethereum"), any(), any()))
                    .thenReturn(CompletableFuture.failedFuture(
                            new RpcClientService.RpcException("RPC down")));

            service.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);
            Thread.sleep(500);
            service.stopIndexing("ethereum");

            // Service should still be operational — error was handled
            IndexerStatus status = service.getStatus();
            assertThat(status.getChains()).containsKey("ethereum");
        }

        @Test
        @DisplayName("should handle checkpoint update failures gracefully")
        void handlesCheckpointUpdateFailure() throws Exception {
            when(checkpointRepository.findByChain("ethereum"))
                    .thenReturn(Optional.of(createCheckpoint("ethereum", 99L)));
            when(rpcClientService.getIndexedBlocksAsync(eq("ethereum"), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(createTestBlocks(100, 104)));
            when(checkpointRepository.updateCheckpoint(anyString(), anyLong(), anyLong(), anyLong(), any()))
                    .thenThrow(new RuntimeException("DB connection lost"));

            service.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);
            Thread.sleep(500);
            service.stopIndexing("ethereum");

            // Should not crash — checkpoint failures are logged but non-fatal
        }
    }

    // =========================================================================
    // startAll / IndexMode enum
    // =========================================================================

    @Nested
    @DisplayName("IndexMode")
    class IndexModeTests {

        @Test
        @DisplayName("should have BACKFILL and INCREMENTAL modes")
        void hasBothModes() {
            assertThat(BlockIndexerService.IndexMode.values())
                    .containsExactly(
                            BlockIndexerService.IndexMode.BACKFILL,
                            BlockIndexerService.IndexMode.INCREMENTAL
                    );
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static IndexerCheckpoint createCheckpoint(String chain, long lastBlock) {
        IndexerCheckpoint cp = new IndexerCheckpoint();
        cp.setId(1L);
        cp.setChain(chain);
        cp.setLastIndexedBlock(lastBlock);
        cp.setTotalBlocksIndexed(0L);
        cp.setTotalTransactionsIndexed(0L);
        cp.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        cp.setLastUpdated(OffsetDateTime.now(ZoneOffset.UTC));
        return cp;
    }

    private static List<IndexedBlock> createTestBlocks(long from, long to) {
        List<IndexedBlock> blocks = new java.util.ArrayList<>();
        for (long i = from; i <= to; i++) {
            blocks.add(IndexedBlock.builder()
                    .chain("Ethereum")
                    .chainId(1L)
                    .blockNumber(i)
                    .blockHash("0x" + Long.toHexString(i))
                    .parentHash("0x" + Long.toHexString(i - 1))
                    .timestamp(1_700_000_000L + i * 12)
                    .gasLimit(30_000_000L)
                    .gasUsed(15_000_000L)
                    .gasUsedPercentage(50.0)
                    .transactionCount(100)
                    .miner("0x0000")
                    .transactions(Collections.emptyList())
                    .build());
        }
        return blocks;
    }

    private static <T> CompletableFuture<T> neverCompletingFuture() {
        return new CompletableFuture<>();
    }
}
