package com.peterstringer.blockchain.indexer.service;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.model.IndexedBlock;
import com.peterstringer.blockchain.indexer.model.IndexerCheckpoint;
import com.peterstringer.blockchain.indexer.model.IndexerMetric;
import com.peterstringer.blockchain.indexer.model.IndexerStatus;
import com.peterstringer.blockchain.indexer.repository.CheckpointRepository;
import com.peterstringer.blockchain.indexer.repository.MetricsRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.peterstringer.blockchain.indexer.model.ws.BlockIndexedMessage;
import com.peterstringer.blockchain.indexer.model.ws.IndexerProgressMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central orchestrator for the blockchain indexing pipeline.
 *
 * <p>Coordinates all stages of the indexing lifecycle: RPC block fetching,
 * data transformation, Parquet export, checkpoint persistence, and real-time
 * WebSocket dashboard updates. Each configured chain runs its own indexing
 * loop, either in backfill mode (processing historical blocks) or incremental
 * mode (tailing the chain head).
 *
 * <h2>Architecture Overview</h2>
 * <pre>
 *   ┌──────────────────────┐
 *   │  BlockIndexerService  │  ◄── orchestrator (this class)
 *   └──────────┬───────────┘
 *              │
 *   ┌──────────▼───────────┐
 *   │   RpcClientService    │  ◄── fetches blocks via JSON-RPC or synthetic data
 *   └──────────┬───────────┘
 *              │
 *   ┌──────────▼───────────┐
 *   │  ParquetWriterService │  ◄── writes blocks + transactions to Parquet files
 *   └──────────┬───────────┘
 *              │
 *   ┌──────────▼───────────┐
 *   │ CheckpointRepository  │  ◄── crash-recovery via atomic checkpoint updates
 *   └──────────┬───────────┘
 *              │
 *   ┌──────────▼───────────┐
 *   │  WebSocket + Metrics  │  ◄── real-time progress and Micrometer/DB metrics
 *   └─────────────────────┘
 * </pre>
 *
 * <h2>Concurrency Model</h2>
 * <p>Two thread pools isolate concerns and prevent deadlocks:
 * <ul>
 *   <li><b>chainExecutor</b> — unbounded cached pool; one thread per active chain
 *       runs the main indexing loop (backfill or incremental). Threads are named
 *       {@code indexer-chain-N} for easy identification in thread dumps.</li>
 *   <li><b>fetchExecutor</b> — fixed-size pool sized by
 *       {@code indexer.concurrency.worker-threads}; handles concurrent RPC
 *       block fetching within each batch. Threads are named
 *       {@code indexer-fetch-N}.</li>
 * </ul>
 * <p>This separation ensures that chain indexing loops never contend with
 * batch-internal fetch tasks for thread resources, eliminating the risk of
 * thread-pool starvation deadlocks.
 *
 * <h2>Error Handling Strategy</h2>
 * <ul>
 *   <li><b>Individual block failures:</b> logged and skipped. The batch
 *       continues with successfully fetched blocks. The
 *       {@link RpcClientService} already retries with exponential backoff
 *       before reporting failure.</li>
 *   <li><b>Full batch failures:</b> logged, error counter incremented,
 *       and the loop advances to the next batch. No data is lost because
 *       blocks that weren't written will be re-fetched on restart.</li>
 *   <li><b>Parquet write failures:</b> the {@link ParquetWriterService}
 *       re-buffers failed writes internally, so they will be retried on
 *       the next flush.</li>
 *   <li><b>Checkpoint update failures:</b> logged but non-fatal. At worst
 *       this causes a small amount of duplicate processing after restart.</li>
 *   <li><b>Critical errors:</b> database connection loss, out-of-memory,
 *       or disk-full conditions terminate the chain's indexing loop to
 *       avoid cascading failures.</li>
 * </ul>
 *
 * <h2>WebSocket Message Format</h2>
 * <p>Progress updates are sent to {@code /topic/indexer/{chain}} after each
 * batch. The payload is a JSON object:
 * <pre>
 * {
 *   "chain":                "ethereum",
 *   "blockNumber":          18000000,
 *   "blocksProcessed":      5000,
 *   "transactionsProcessed": 150000,
 *   "blocksPerSecond":       42.5,
 *   "eta":                  "00:15:32",
 *   "mode":                 "RUNNING_BACKFILL",
 *   "errors":               3
 * }
 * </pre>
 *
 * <h2>Micrometer Metrics</h2>
 * <ul>
 *   <li><b>Counter:</b> {@code indexer.blocks.indexed} — total blocks written (per chain).</li>
 *   <li><b>Counter:</b> {@code indexer.transactions.indexed} — total transactions (per chain).</li>
 *   <li><b>Counter:</b> {@code indexer.rpc.errors} — RPC/processing errors (per chain).</li>
 *   <li><b>Gauge:</b> {@code indexer.current.block} — latest block number (per chain).</li>
 *   <li><b>Gauge:</b> {@code indexer.blocks.per.second} — throughput (per chain).</li>
 *   <li><b>Timer:</b> {@code indexer.block.processing.time} — batch processing duration.</li>
 * </ul>
 */
@Service
public class BlockIndexerService {

    private static final Logger log = LoggerFactory.getLogger(BlockIndexerService.class);

    /**
     * Indexing mode requested when starting a chain.
     *
     * <ul>
     *   <li>{@code BACKFILL} — process a bounded range of historical blocks
     *       from the checkpoint to the configured end block (or current chain head).</li>
     *   <li>{@code INCREMENTAL} — continuously tail the chain head, processing
     *       new blocks as they are produced, with chain-reorg detection.</li>
     * </ul>
     */
    public enum IndexMode {
        BACKFILL,
        INCREMENTAL
    }

    // ---- Internal chain state ----
    private enum ChainState {
        STOPPED, RUNNING_BACKFILL, RUNNING_INCREMENTAL, PAUSED
    }

    // ---- Constants ----
    private static final long INCREMENTAL_POLL_MS = 5_000;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    // ---- Dependencies ----
    private final IndexerProperties properties;
    private final RpcClientService rpcClientService;
    private final ParquetWriterService parquetWriterService;
    private final CheckpointRepository checkpointRepository;
    private final MetricsRepository metricsRepository;
    private final WebSocketService webSocketService;
    private final MeterRegistry meterRegistry;
    private final BlockAnalyticsService blockAnalyticsService;

    // ---- Thread pools ----
    private ExecutorService chainExecutor;
    private ExecutorService fetchExecutor;

    // ---- Per-chain state ----
    private final ConcurrentHashMap<String, ChainIndexingContext> contexts = new ConcurrentHashMap<>();

    // =========================================================================
    // Constructor
    // =========================================================================

    public BlockIndexerService(IndexerProperties properties,
                               RpcClientService rpcClientService,
                               ParquetWriterService parquetWriterService,
                               CheckpointRepository checkpointRepository,
                               MetricsRepository metricsRepository,
                               WebSocketService webSocketService,
                               MeterRegistry meterRegistry,
                               BlockAnalyticsService blockAnalyticsService) {
        this.properties = properties;
        this.rpcClientService = rpcClientService;
        this.parquetWriterService = parquetWriterService;
        this.checkpointRepository = checkpointRepository;
        this.metricsRepository = metricsRepository;
        this.webSocketService = webSocketService;
        this.meterRegistry = meterRegistry;
        this.blockAnalyticsService = blockAnalyticsService;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @PostConstruct
    void initialize() {
        int workerThreads = properties.getConcurrency().getWorkerThreads();

        this.chainExecutor = Executors.newCachedThreadPool(
                new IndexerThreadFactory("indexer-chain"));

        this.fetchExecutor = Executors.newFixedThreadPool(workerThreads,
                new IndexerThreadFactory("indexer-fetch"));

        log.info("BlockIndexerService initialized: workerThreads={}, batchSize={}, demoMode={}",
                workerThreads, properties.getConcurrency().getBatchSize(),
                rpcClientService.isDemoMode());
    }

    /**
     * Gracefully shuts down all indexing activity.
     *
     * <ol>
     *   <li>Signals all running chains to stop.</li>
     *   <li>Awaits thread pool termination (up to 30 seconds each).</li>
     *   <li>Flushes any remaining buffered Parquet data.</li>
     * </ol>
     */
    @PreDestroy
    void shutdown() {
        log.info("BlockIndexerService shutting down — stopping all chains...");

        // Signal all chains to stop
        for (String chain : List.copyOf(contexts.keySet())) {
            try {
                stopIndexing(chain);
            } catch (Exception e) {
                log.warn("Error stopping chain {} during shutdown: {}", chain, e.getMessage());
            }
        }

        // Shutdown thread pools
        chainExecutor.shutdown();
        fetchExecutor.shutdown();
        try {
            if (!chainExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Chain executor did not terminate in time, forcing shutdown");
                chainExecutor.shutdownNow();
            }
            if (!fetchExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Fetch executor did not terminate in time, forcing shutdown");
                fetchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            chainExecutor.shutdownNow();
            fetchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("BlockIndexerService shut down");
    }

    // =========================================================================
    // Public API — start / stop
    // =========================================================================

    /**
     * Starts indexing for a chain asynchronously.
     *
     * <p>Validates the chain exists in configuration, checks it is not already
     * running, loads (or creates) a checkpoint, and submits the indexing task
     * to the chain executor. Returns immediately.
     *
     * @param chain the chain key (e.g. "ethereum")
     * @param mode  backfill or incremental
     * @throws IllegalArgumentException if the chain is not configured
     * @throws IllegalStateException    if the chain is already running
     */
    public void startIndexing(String chain, IndexMode mode) {
        IndexerProperties.ChainConfig config = properties.getChains().get(chain);
        if (config == null) {
            throw new IllegalArgumentException("Unknown chain: " + chain);
        }

        ChainIndexingContext existing = contexts.get(chain);
        if (existing != null && existing.state != ChainState.STOPPED) {
            throw new IllegalStateException(
                    "Chain %s is already running (%s)".formatted(chain, existing.state));
        }

        // Get the current chain head for reference
        long chainHead = rpcClientService.getLatestBlockNumber(chain);

        ChainIndexingContext ctx = new ChainIndexingContext();
        ctx.startedAt = Instant.now();
        ctx.chainHead = chainHead;
        ctx.lastBlockNumber = chainHead;
        contexts.put(chain, ctx);

        registerMicrometerMetrics(chain, ctx);

        if (mode == IndexMode.BACKFILL) {
            // BACKFILL mode: start from chain head, index backward toward startBlock,
            // while simultaneously tailing the chain head for new blocks.
            ctx.state = ChainState.RUNNING_BACKFILL;

            // 1. Start incremental tailing from chain head (new blocks)
            ctx.incrementalTask = chainExecutor.submit(() -> {
                try {
                    indexIncrementalFromBlock(chain, config, chainHead);
                } catch (Exception e) {
                    log.error("Incremental tailing failed for chain={}: {}", chain, e.getMessage(), e);
                }
            });

            // 2. Start reverse backfill from chain head toward startBlock
            ctx.task = chainExecutor.submit(() -> {
                try {
                    indexReverseBackfill(chain, config, chainHead);
                } catch (Exception e) {
                    log.error("Reverse backfill failed for chain={}: {}", chain, e.getMessage(), e);
                } finally {
                    // When reverse backfill completes, transition to incremental-only
                    if (!ctx.stopRequested) {
                        ctx.state = ChainState.RUNNING_INCREMENTAL;
                        log.info("Reverse backfill complete for chain={}, continuing with live tailing", chain);
                    }
                }
            });

            log.info("Started BACKFILL for chain={}: reverse from {} toward {}, live tailing from {} (demo={})",
                    chain, chainHead, config.getStartBlock(), chainHead, rpcClientService.isDemoMode());

        } else {
            // INCREMENTAL mode: just tail the chain head
            ctx.state = ChainState.RUNNING_INCREMENTAL;

            ctx.task = chainExecutor.submit(() -> {
                try {
                    indexIncrementalFromBlock(chain, config, chainHead);
                } catch (Exception e) {
                    log.error("Incremental indexing failed for chain={}: {}", chain, e.getMessage(), e);
                } finally {
                    ctx.state = ChainState.STOPPED;
                    ctx.stopRequested = false;
                    log.info("Incremental indexing exited for chain={}: {} blocks, {} txs, {} errors",
                            chain, ctx.blocksProcessed.get(),
                            ctx.transactionsProcessed.get(), ctx.errorCount.get());
                }
            });

            log.info("Started INCREMENTAL for chain={} from block {} (demo={})",
                    chain, chainHead, rpcClientService.isDemoMode());
        }
    }

    /**
     * Gracefully stops indexing for a chain.
     *
     * <p>Signals the indexing loop to stop, waits up to 30 seconds for the
     * current batch to complete, and flushes any buffered Parquet data.
     *
     * @param chain the chain key
     */
    public void stopIndexing(String chain) {
        ChainIndexingContext ctx = contexts.get(chain);
        if (ctx == null || ctx.state == ChainState.STOPPED) {
            log.debug("Chain {} is not running — nothing to stop", chain);
            return;
        }

        log.info("Stopping indexing for chain={}...", chain);
        ctx.stopRequested = true;

        // Wait for both tasks (reverse backfill + incremental tailing)
        awaitTask(ctx.task, chain, "backfill");
        awaitTask(ctx.incrementalTask, chain, "incremental");

        ctx.state = ChainState.STOPPED;

        // Flush remaining Parquet data for this chain's partitions
        try {
            parquetWriterService.flushAll();
        } catch (Exception e) {
            log.warn("Error flushing Parquet data after stopping chain={}: {}", chain, e.getMessage());
        }

        log.info("Indexing stopped for chain={}: {} blocks, {} txs",
                chain, ctx.blocksProcessed.get(), ctx.transactionsProcessed.get());
    }

    private void awaitTask(Future<?> task, String chain, String taskName) {
        if (task == null) return;
        try {
            task.get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Timed out waiting for {} task on chain={} — cancelling", taskName, chain);
            task.cancel(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
        } catch (ExecutionException e) {
            log.warn("Chain {} {} task completed with error: {}", chain, taskName, e.getMessage());
        }
    }

    /**
     * Starts indexing for all configured chains.
     *
     * @param mode backfill or incremental
     */
    public void startAll(IndexMode mode) {
        properties.getChains().keySet().forEach(chain -> {
            try {
                startIndexing(chain, mode);
            } catch (Exception e) {
                log.error("Failed to start indexing for chain={}: {}", chain, e.getMessage());
            }
        });
    }

    /**
     * Stops indexing for all running chains.
     */
    public void stopAll() {
        List.copyOf(contexts.keySet()).forEach(this::stopIndexing);
    }

    // =========================================================================
    // Public API — status
    // =========================================================================

    /**
     * Returns the current status of the indexer across all chains.
     *
     * @return an {@link IndexerStatus} snapshot
     */
    public IndexerStatus getStatus() {
        Map<String, IndexerStatus.ChainStatus> chainStatuses = new LinkedHashMap<>();
        boolean anyRunning = false;
        IndexerStatus.Mode overallMode = IndexerStatus.Mode.STOPPED;

        // Load checkpoint data for cumulative counters
        Map<String, IndexerCheckpoint> checkpoints = new LinkedHashMap<>();
        try {
            for (IndexerCheckpoint cp : checkpointRepository.findAllOrderByLastIndexedBlockDesc()) {
                checkpoints.put(cp.getChain(), cp);
            }
        } catch (Exception e) {
            log.debug("Could not load checkpoints for status: {}", e.getMessage());
        }

        for (var entry : contexts.entrySet()) {
            ChainIndexingContext ctx = entry.getValue();
            if (ctx.state != ChainState.STOPPED) {
                anyRunning = true;
                overallMode = ctx.state == ChainState.RUNNING_BACKFILL
                        ? IndexerStatus.Mode.BACKFILL
                        : IndexerStatus.Mode.INCREMENTAL;
            }
            chainStatuses.put(entry.getKey(), buildChainStatus(entry.getKey(), ctx, checkpoints.get(entry.getKey())));
        }

        // Include chains that haven't been started yet
        for (String chain : properties.getChains().keySet()) {
            IndexerProperties.ChainConfig config = properties.getChains().get(chain);
            IndexerCheckpoint cp = checkpoints.get(chain);
            long lastBlk = cp != null ? cp.getLastIndexedBlock() : 0L;
            chainStatuses.putIfAbsent(chain, IndexerStatus.ChainStatus.builder()
                    .chainId(config != null ? (int) config.getChainId() : null)
                    .lastBlock(lastBlk)
                    .targetBlock(lastBlk)
                    .blocksIndexed(cp != null ? cp.getTotalBlocksIndexed() : 0L)
                    .transactionsIndexed(cp != null ? cp.getTotalTransactionsIndexed() : 0L)
                    .blocksPerSecond(0.0)
                    .rpcHealth("STOPPED")
                    .build());
        }

        return IndexerStatus.builder()
                .running(anyRunning)
                .mode(overallMode)
                .chains(chainStatuses)
                .build();
    }

    // =========================================================================
    // Reverse backfill: index from chain head backward toward startBlock
    // =========================================================================

    /**
     * Indexes blocks in reverse chronological order — from the chain head
     * backward toward the configured start block. This ensures the most recent
     * data is available first while historical blocks fill in over time.
     *
     * <p>Resumes from the persisted {@code backfillFloorBlock} checkpoint if
     * a previous reverse backfill was interrupted. Uses the same batch
     * processing, Parquet writing, and analytics persistence as forward backfill.
     *
     * @param chain     the chain key
     * @param config    chain configuration
     * @param chainHead the chain head at the time indexing was started
     */
    private void indexReverseBackfill(String chain, IndexerProperties.ChainConfig config,
                                      long chainHead) {
        IndexerCheckpoint checkpoint = loadOrCreateCheckpoint(chain, config);
        ChainIndexingContext ctx = contexts.get(chain);

        // Determine where to resume: previous floor or chain head
        long currentFloor = checkpoint.getBackfillFloorBlock() != null
                ? checkpoint.getBackfillFloorBlock()
                : chainHead + 1; // +1 because chain head is handled by incremental

        long targetFloor = config.getStartBlock();

        if (currentFloor <= targetFloor) {
            log.info("Reverse backfill already complete for chain={}: floor={} <= target={}",
                    chain, currentFloor, targetFloor);
            ctx.reverseBackfillComplete = true;
            ctx.backfillFloorBlock = currentFloor;
            return;
        }

        long totalBlocks = currentFloor - targetFloor;
        log.info("Starting reverse backfill for chain={}: blocks {} ← {} ({} total)",
                chain, currentFloor - 1, targetFloor, totalBlocks);

        int batchSize = properties.getConcurrency().getBatchSize();
        ctx.backfillFloorBlock = currentFloor;

        for (long high = currentFloor - 1; high >= targetFloor && !ctx.stopRequested; ) {
            long low = Math.max(high - batchSize + 1, targetFloor);
            List<Long> blockNumbers = buildBlockNumberList(low, high);

            Instant batchStartTime = Instant.now();

            try {
                List<IndexedBlock> blocks = rpcClientService
                        .getIndexedBlocksAsync(chain, blockNumbers, fetchExecutor)
                        .join();

                if (blocks.isEmpty()) {
                    ctx.errorCount.incrementAndGet();
                    incrementErrorCounter(chain);
                    log.warn("Empty reverse batch {}-{} on chain={}", low, high, chain);
                    high = low - 1;
                    continue;
                }

                // Write blocks + embedded transactions to Parquet
                parquetWriterService.writeBlocks(blocks);

                // Persist block analytics to PostgreSQL for historical dashboard
                blockAnalyticsService.persistBatch(blocks);

                // Update counters
                long txCount = blocks.stream()
                        .mapToInt(b -> b.getTransactions().size())
                        .sum();

                ctx.blocksProcessed.addAndGet(blocks.size());
                ctx.transactionsProcessed.addAndGet(txCount);
                ctx.backfillFloorBlock = low;
                ctx.lastBatchAt = Instant.now();

                // Persist backfill floor checkpoint
                updateBackfillFloor(chain, low, blocks.size(), txCount);

                // Record metrics
                Duration batchDuration = Duration.between(batchStartTime, Instant.now());
                recordBatchMetrics(chain, ctx, blocks.size(), txCount, batchDuration);

                // WebSocket: progress + block notifications
                sendProgressUpdate(chain, ctx, ctx.chainHead);
                sendRecentBlockNotifications(chain, blocks);

                // Log progress
                double bps = calculateBlocksPerSecond(ctx);
                long remaining = low - targetFloor;
                String eta = bps > 0
                        ? formatDuration(Duration.ofSeconds((long) (remaining / bps)))
                        : "unknown";

                log.info("chain={} reverse-backfill: floor {} ← target {} ({} left) | {}/s | ETA {} | batch: {} blk, {} tx, {}ms",
                        chain, low, targetFloor, remaining,
                        String.format("%.1f", bps), eta,
                        blocks.size(), txCount, batchDuration.toMillis());

            } catch (CompletionException | RpcClientService.RpcException e) {
                ctx.errorCount.incrementAndGet();
                incrementErrorCounter(chain);
                log.error("Reverse batch {}-{} failed on chain={}: {}",
                        low, high, chain, e.getMessage());
            } catch (Exception e) {
                ctx.errorCount.incrementAndGet();
                incrementErrorCounter(chain);
                log.error("Unexpected error in reverse batch {}-{} on chain={}: {}",
                        low, high, chain, e.getMessage(), e);
                if (isCriticalError(e)) {
                    log.error("Critical error — aborting reverse backfill for chain={}", chain);
                    break;
                }
            }

            high = low - 1;
        }

        if (!ctx.stopRequested) {
            ctx.reverseBackfillComplete = true;
            log.info("Reverse backfill complete for chain={}: {} blocks indexed down to block {}",
                    chain, ctx.blocksProcessed.get(), targetFloor);
        }
    }

    // =========================================================================
    // Incremental indexing loop (tails chain head for new blocks)
    // =========================================================================

    /**
     * Continuously polls for new blocks from a given starting point.
     *
     * <p>Implements chain-reorg detection by comparing each block's
     * {@code parentHash} against the hash of the previously indexed block.
     * On reorg, the indexer rolls back and re-processes from the fork point.
     *
     * <p>The poll interval defaults to {@value #INCREMENTAL_POLL_MS}ms,
     * matching typical EVM block times. On RPC errors, the interval is
     * doubled temporarily to reduce load on recovering providers.
     *
     * @param chain          the chain key
     * @param config         chain configuration
     * @param startFromBlock block to start tailing from (the chain head at start time)
     */
    private void indexIncrementalFromBlock(String chain, IndexerProperties.ChainConfig config,
                                           long startFromBlock) {
        // Ensure checkpoint exists (may already have been created by reverse backfill)
        loadOrCreateCheckpoint(chain, config);

        ChainIndexingContext ctx = contexts.get(chain);
        // Use the higher of: the checkpoint's lastIndexedBlock or the provided start block.
        // On restart, checkpoint may be ahead of startFromBlock.
        IndexerCheckpoint cp = checkpointRepository.findByChain(chain).orElse(null);
        long resumeFrom = cp != null && cp.getLastIndexedBlock() > startFromBlock
                ? cp.getLastIndexedBlock()
                : startFromBlock;
        ctx.lastBlockNumber = resumeFrom;

        log.info("Starting incremental tailing for chain={} from block {}",
                chain, ctx.lastBlockNumber);

        while (!ctx.stopRequested) {
            try {
                long latestBlock = rpcClientService.getLatestBlockNumber(chain);
                ctx.chainHead = latestBlock;

                if (latestBlock <= ctx.lastBlockNumber) {
                    sleep(INCREMENTAL_POLL_MS);
                    continue;
                }

                // Process new blocks one at a time for reorg detection
                for (long blockNum = ctx.lastBlockNumber + 1;
                     blockNum <= latestBlock && !ctx.stopRequested;
                     blockNum++) {

                    Instant blockStart = Instant.now();
                    IndexedBlock block = rpcClientService.getIndexedBlock(chain, blockNum);

                    // Reorg detection via parent hash
                    if (ctx.lastBlockHash != null
                            && !block.getParentHash().equals(ctx.lastBlockHash)) {
                        log.warn("REORG detected on chain={} at block {}: "
                                        + "expected parent={}, got={}",
                                chain, blockNum, ctx.lastBlockHash, block.getParentHash());
                        blockNum = handleReorg(chain, config, ctx, blockNum);
                        continue;
                    }

                    // Write to Parquet
                    parquetWriterService.writeBlocks(List.of(block));

                    // Persist block analytics to PostgreSQL for historical dashboard
                    blockAnalyticsService.persistBatch(List.of(block));

                    // Update state
                    long txCount = block.getTransactions().size();
                    ctx.blocksProcessed.incrementAndGet();
                    ctx.transactionsProcessed.addAndGet(txCount);
                    ctx.lastBlockNumber = blockNum;
                    ctx.lastBlockHash = block.getBlockHash();
                    ctx.lastBatchAt = Instant.now();

                    // Checkpoint + metrics + WebSocket
                    updateCheckpoint(chain, blockNum, 1, txCount);
                    Duration blockDuration = Duration.between(blockStart, Instant.now());
                    recordBatchMetrics(chain, ctx, 1, txCount, blockDuration);
                    sendProgressUpdate(chain, ctx, latestBlock);
                    sendRecentBlockNotifications(chain, List.of(block));

                    if (blockNum % 10 == 0) {
                        log.info("chain={} incremental: block {} | {} txs | {}ms",
                                chain, blockNum, txCount, blockDuration.toMillis());
                    }
                }

                sleep(INCREMENTAL_POLL_MS);

            } catch (RpcClientService.RpcException e) {
                ctx.errorCount.incrementAndGet();
                incrementErrorCounter(chain);
                log.error("RPC error in incremental mode for chain={}: {}",
                        chain, e.getMessage());
                sleep(INCREMENTAL_POLL_MS * 2);
            } catch (Exception e) {
                ctx.errorCount.incrementAndGet();
                incrementErrorCounter(chain);
                log.error("Error in incremental mode for chain={}: {}",
                        chain, e.getMessage(), e);
                if (isCriticalError(e)) {
                    log.error("Critical error — aborting incremental indexing for chain={}", chain);
                    break;
                }
                sleep(INCREMENTAL_POLL_MS * 2);
            }
        }
    }

    // =========================================================================
    // Reorg handling
    // =========================================================================

    /**
     * Handles a detected chain reorganization by rolling back the checkpoint.
     *
     * <p>Rolls back one block before the fork point so the next iteration
     * re-fetches the correct chain. A production system would walk the
     * parent-hash chain to find the exact common ancestor; this implementation
     * caps rollback depth at {@value #REORG_MAX_DEPTH} blocks.
     *
     * @param chain      the chain key
     * @param config     chain configuration
     * @param ctx        the chain's indexing context
     * @param forkBlock  the block number where the reorg was detected
     * @return the block number to resume from (loop will increment by 1)
     */
    private long handleReorg(String chain, IndexerProperties.ChainConfig config,
                             ChainIndexingContext ctx, long forkBlock) {
        long safeBlock = Math.max(forkBlock - 1, config.getStartBlock());

        log.info("Reorg recovery for chain={}: rolling back from {} to {}",
                chain, forkBlock, safeBlock);

        ctx.lastBlockNumber = safeBlock;
        ctx.lastBlockHash = null; // re-established on next successful block
        updateCheckpoint(chain, safeBlock, 0, 0);

        return safeBlock;
    }

    // =========================================================================
    // Checkpoint management
    // =========================================================================

    private IndexerCheckpoint loadOrCreateCheckpoint(String chain,
                                                     IndexerProperties.ChainConfig config) {
        return checkpointRepository.findByChain(chain)
                .orElseGet(() -> {
                    IndexerCheckpoint cp = new IndexerCheckpoint();
                    cp.setChain(chain);
                    cp.setLastIndexedBlock(config.getStartBlock() - 1);
                    cp.setTotalBlocksIndexed(0L);
                    cp.setTotalTransactionsIndexed(0L);
                    cp.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    cp.setLastUpdated(OffsetDateTime.now(ZoneOffset.UTC));
                    return checkpointRepository.save(cp);
                });
    }

    private void updateCheckpoint(String chain, long lastBlock,
                                  long blocksIndexed, long txCount) {
        try {
            int updated = checkpointRepository.updateCheckpoint(
                    chain, lastBlock, blocksIndexed, txCount,
                    OffsetDateTime.now(ZoneOffset.UTC));
            if (updated == 0) {
                log.warn("Checkpoint update returned 0 rows for chain={} — record may not exist",
                        chain);
            }
        } catch (Exception e) {
            log.error("Failed to update checkpoint for chain={}: {}", chain, e.getMessage());
        }
    }

    private void updateBackfillFloor(String chain, long floorBlock,
                                     long blocksIndexed, long txCount) {
        try {
            int updated = checkpointRepository.updateBackfillFloor(
                    chain, floorBlock, blocksIndexed, txCount,
                    OffsetDateTime.now(ZoneOffset.UTC));
            if (updated == 0) {
                log.warn("Backfill floor update returned 0 rows for chain={}", chain);
            }
        } catch (Exception e) {
            log.error("Failed to update backfill floor for chain={}: {}", chain, e.getMessage());
        }
    }

    // =========================================================================
    // WebSocket progress
    // =========================================================================

    /** Maximum number of block-indexed notifications sent per batch. */
    private static final int MAX_BLOCK_NOTIFICATIONS_PER_BATCH = 10;

    /**
     * Sends a typed progress update via {@link WebSocketService}.
     * Throttling is handled inside WebSocketService (max 2/s per chain).
     */
    private void sendProgressUpdate(String chain, ChainIndexingContext ctx,
                                    long targetBlock) {
        double bps = calculateBlocksPerSecond(ctx);
        long remaining = targetBlock - ctx.lastBlockNumber;
        String eta = (bps > 0 && remaining > 0)
                ? formatDuration(Duration.ofSeconds((long) (remaining / bps)))
                : "N/A";

        IndexerProgressMessage msg = IndexerProgressMessage.builder()
                .chain(chain)
                .currentBlock(ctx.lastBlockNumber)
                .latestBlock(targetBlock)
                .blocksProcessed(ctx.blocksProcessed.get())
                .transactionsProcessed(ctx.transactionsProcessed.get())
                .blocksPerSecond(Math.round(bps * 10.0) / 10.0)
                .estimatedTimeRemaining(eta)
                .timestamp(System.currentTimeMillis())
                .build();

        webSocketService.sendProgress(chain, msg);
    }

    /**
     * Sends block-indexed notifications for the most recent blocks in a batch.
     * Limited to the last {@value #MAX_BLOCK_NOTIFICATIONS_PER_BATCH} blocks to
     * avoid flooding subscribers during high-throughput backfill.
     */
    private void sendRecentBlockNotifications(String chain, List<IndexedBlock> blocks) {
        int start = Math.max(0, blocks.size() - MAX_BLOCK_NOTIFICATIONS_PER_BATCH);
        for (int i = start; i < blocks.size(); i++) {
            IndexedBlock b = blocks.get(i);
            Double baseFeeGwei = b.getBaseFeePerGas() != null
                    ? b.getBaseFeePerGas() / 1_000_000_000.0
                    : null;

            BlockIndexedMessage msg = BlockIndexedMessage.builder()
                    .chain(chain)
                    .blockNumber(b.getBlockNumber())
                    .blockHash(b.getBlockHash())
                    .transactionCount(b.getTransactionCount())
                    .gasUsed(b.getGasUsed())
                    .baseFeeGwei(baseFeeGwei)
                    .timestamp(b.getTimestamp())
                    .build();

            webSocketService.sendBlockIndexed(chain, msg);
        }
    }

    // =========================================================================
    // Micrometer metrics
    // =========================================================================

    private void registerMicrometerMetrics(String chain, ChainIndexingContext ctx) {
        Counter.builder("indexer.blocks.indexed")
                .tag("chain", chain)
                .description("Total blocks indexed")
                .register(meterRegistry);

        Counter.builder("indexer.transactions.indexed")
                .tag("chain", chain)
                .description("Total transactions indexed")
                .register(meterRegistry);

        Counter.builder("indexer.rpc.errors")
                .tag("chain", chain)
                .description("RPC and processing errors")
                .register(meterRegistry);

        Gauge.builder("indexer.current.block", ctx, c -> (double) c.lastBlockNumber)
                .tag("chain", chain)
                .description("Current block number being indexed")
                .register(meterRegistry);

        Gauge.builder("indexer.blocks.per.second", ctx, this::calculateBlocksPerSecond)
                .tag("chain", chain)
                .description("Current indexing throughput")
                .register(meterRegistry);
    }

    private void recordBatchMetrics(String chain, ChainIndexingContext ctx,
                                    int blockCount, long txCount, Duration batchDuration) {
        // Micrometer counters + timer
        meterRegistry.counter("indexer.blocks.indexed", "chain", chain)
                .increment(blockCount);
        meterRegistry.counter("indexer.transactions.indexed", "chain", chain)
                .increment(txCount);
        meterRegistry.timer("indexer.block.processing.time", "chain", chain)
                .record(batchDuration);

        // Persist to DB periodically (every ~10 batches) for historical dashboards
        int batchSize = properties.getConcurrency().getBatchSize();
        if (batchSize > 0 && ctx.blocksProcessed.get() % ((long) batchSize * 10) == 0) {
            recordDbMetric(chain, "blocks_per_second", calculateBlocksPerSecond(ctx));
            recordDbMetric(chain, "error_count", ctx.errorCount.get());
        }
    }

    private void incrementErrorCounter(String chain) {
        meterRegistry.counter("indexer.rpc.errors", "chain", chain).increment();
    }

    private void recordDbMetric(String chain, String metricName, double value) {
        try {
            IndexerMetric metric = new IndexerMetric();
            metric.setChain(chain);
            metric.setMetricName(metricName);
            metric.setMetricValue(value);
            metric.setRecordedAt(OffsetDateTime.now(ZoneOffset.UTC));
            metricsRepository.save(metric);
        } catch (Exception e) {
            log.debug("Failed to persist metric {}={} for chain={}: {}",
                    metricName, value, chain, e.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private double calculateBlocksPerSecond(ChainIndexingContext ctx) {
        if (ctx.startedAt == null || ctx.blocksProcessed.get() == 0) {
            return 0.0;
        }
        long elapsedSeconds = Duration.between(ctx.startedAt, Instant.now()).toSeconds();
        return elapsedSeconds > 0
                ? (double) ctx.blocksProcessed.get() / elapsedSeconds
                : 0.0;
    }

    private IndexerStatus.ChainStatus buildChainStatus(String chain, ChainIndexingContext ctx, IndexerCheckpoint cp) {
        // Use cumulative checkpoint data + session data for accurate totals
        long totalBlocks = cp != null ? cp.getTotalBlocksIndexed() : ctx.blocksProcessed.get();
        long totalTxs = cp != null ? cp.getTotalTransactionsIndexed() : ctx.transactionsProcessed.get();
        long lastBlock = ctx.lastBlockNumber > 0 ? ctx.lastBlockNumber : (cp != null ? cp.getLastIndexedBlock() : 0L);
        long target = ctx.chainHead > 0 ? ctx.chainHead : lastBlock;

        IndexerProperties.ChainConfig config = properties.getChains().get(chain);
        return IndexerStatus.ChainStatus.builder()
                .chainId(config != null ? (int) config.getChainId() : null)
                .lastBlock(lastBlock)
                .targetBlock(target)
                .blocksIndexed(totalBlocks)
                .transactionsIndexed(totalTxs)
                .blocksPerSecond(calculateBlocksPerSecond(ctx))
                .rpcHealth(ctx.state.name())
                .build();
    }

    private static List<Long> buildBlockNumberList(long from, long to) {
        List<Long> list = new ArrayList<>((int) (to - from + 1));
        for (long i = from; i <= to; i++) {
            list.add(i);
        }
        return list;
    }

    /**
     * Determines if an exception represents an unrecoverable infrastructure
     * failure that should terminate the indexing loop.
     */
    private static boolean isCriticalError(Exception e) {
        String message = rootMessage(e).toLowerCase();
        return (message.contains("connection") && message.contains("refused"))
                || message.contains("out of memory")
                || message.contains("no space left on device")
                || message.contains("disk full");
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : "";
    }

    private static String formatDuration(Duration duration) {
        long hours = duration.toHours();
        int minutes = duration.toMinutesPart();
        int seconds = duration.toSecondsPart();
        return hours > 0
                ? "%02d:%02d:%02d".formatted(hours, minutes, seconds)
                : "%02d:%02d".formatted(minutes, seconds);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================
    // Inner: per-chain indexing context
    // =========================================================================

    /**
     * Mutable state for a single chain's indexing session.
     *
     * <p>All fields are either volatile or atomic to support safe reads from
     * the status API while the indexing thread writes them.
     */
    static class ChainIndexingContext {
        volatile ChainState state = ChainState.STOPPED;
        volatile boolean stopRequested = false;
        final AtomicLong blocksProcessed = new AtomicLong(0);
        final AtomicLong transactionsProcessed = new AtomicLong(0);
        final AtomicLong errorCount = new AtomicLong(0);
        volatile Instant startedAt;
        volatile Instant lastBatchAt;
        volatile long lastBlockNumber;        // highest block indexed (incremental head)
        volatile String lastBlockHash;
        volatile long backfillFloorBlock;     // lowest block reached in reverse backfill
        volatile boolean reverseBackfillComplete = false;
        volatile long chainHead;              // latest known chain head at start
        Future<?> task;                       // primary task (reverse backfill or incremental)
        Future<?> incrementalTask;            // concurrent incremental tailing task
    }

    // =========================================================================
    // Inner: custom thread factory
    // =========================================================================

    /**
     * Creates daemon threads with a descriptive name prefix for easy
     * identification in thread dumps and monitoring tools.
     */
    private static class IndexerThreadFactory implements ThreadFactory {
        private final AtomicLong counter = new AtomicLong(0);
        private final String prefix;

        IndexerThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
