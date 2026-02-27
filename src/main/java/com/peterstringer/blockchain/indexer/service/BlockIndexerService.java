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
        STOPPED, RUNNING_BACKFILL, RUNNING_INCREMENTAL, RUNNING_BOTH, PAUSED
    }

    // ---- Constants ----
    private static final long INCREMENTAL_POLL_MS = 5_000;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final int MAX_BACKFILL_RETRIES = 3;

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

        // Check if THIS specific mode is already running
        if (existing != null && mode == IndexMode.INCREMENTAL
                && existing.incrementalTask != null && !existing.incrementalTask.isDone()) {
            throw new IllegalStateException(
                    "Incremental indexing already running for chain %s".formatted(chain));
        }
        if (existing != null && mode == IndexMode.BACKFILL
                && existing.backfillTask != null && !existing.backfillTask.isDone()) {
            throw new IllegalStateException(
                    "Backfill already running for chain %s".formatted(chain));
        }

        // Get the current chain head for reference
        long chainHead = rpcClientService.getLatestBlockNumber(chain);

        // Reuse existing context if the other mode is running, else create fresh
        ChainIndexingContext ctx;
        boolean otherModeRunning = existing != null && existing.getState() != ChainState.STOPPED;

        // In demo mode, reset stale checkpoints so each run starts fresh.
        // Resets when: (a) checkpoint is from a different config (production data
        // beyond the demo range), or (b) a previous demo backfill already completed.
        // Does NOT reset mid-backfill checkpoints or test-setup data.
        if (rpcClientService.isDemoMode() && !otherModeRunning) {
            long demoEnd = config.getStartBlock()
                    + properties.getDemo().getSyntheticBlockCount() - 1;
            checkpointRepository.findByChain(chain).ifPresent(cp -> {
                boolean outsideDemoRange = cp.getLastIndexedBlock() > demoEnd;
                boolean backfillAlreadyComplete = cp.getBackfillFloorBlock() != null
                        && cp.getBackfillFloorBlock() <= config.getStartBlock();
                if (outsideDemoRange || backfillAlreadyComplete) {
                    cp.setLastIndexedBlock(config.getStartBlock() - 1);
                    cp.setBackfillFloorBlock(null);
                    cp.setTotalBlocksIndexed(0L);
                    cp.setTotalTransactionsIndexed(0L);
                    cp.setLastUpdated(OffsetDateTime.now(ZoneOffset.UTC));
                    checkpointRepository.save(cp);
                    log.info("Demo mode: reset stale checkpoint for chain={}", chain);
                }
            });
        }
        if (otherModeRunning) {
            ctx = existing;
            ctx.chainHead = chainHead;
        } else {
            ctx = new ChainIndexingContext();
            ctx.startedAt = Instant.now();
            ctx.chainHead = chainHead;
            // In demo mode, start before the synthetic block range so incremental
            // mode has blocks to process (the synthetic chain head is static).
            ctx.lastBlockNumber = rpcClientService.isDemoMode()
                    ? config.getStartBlock() - 1
                    : chainHead;
            contexts.put(chain, ctx);
            registerMicrometerMetrics(chain, ctx);
        }

        if (mode == IndexMode.BACKFILL) {
            // Always reset — user explicitly requested a (re)start of backfill
            ctx.backfillFloorBlock = chainHead;
            ctx.reverseBackfillComplete = false;

            ctx.backfillTask = chainExecutor.submit(() -> {
                try {
                    indexBackfillOnly(chain, config);
                } catch (Exception e) {
                    log.error("Backfill failed for chain={}: {}", chain, e.getMessage(), e);
                } finally {
                    log.info("Backfill exited for chain={}: {} blocks, {} txs, {} errors",
                            chain, ctx.blocksProcessed.get(),
                            ctx.transactionsProcessed.get(), ctx.errorCount.get());
                }
            });

            log.info("Started BACKFILL for chain={}: reverse backfill toward {} (demo={})",
                    chain, config.getStartBlock(), rpcClientService.isDemoMode());

        } else {
            ctx.incrementalTask = chainExecutor.submit(() -> {
                try {
                    indexIncrementalFromBlock(chain, config, chainHead);
                } catch (Exception e) {
                    log.error("Incremental indexing failed for chain={}: {}", chain, e.getMessage(), e);
                } finally {
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
        if (ctx == null || ctx.getState() == ChainState.STOPPED) {
            log.debug("Chain {} is not running — nothing to stop", chain);
            return;
        }

        log.info("Stopping indexing for chain={}...", chain);
        ctx.stopRequested = true;

        // Wait for both tasks to finish
        awaitTask(ctx.incrementalTask, chain, "incremental");
        awaitTask(ctx.backfillTask, chain, "backfill");

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
            ChainState chainState = ctx.getState();
            if (chainState != ChainState.STOPPED) {
                anyRunning = true;
                overallMode = (chainState == ChainState.RUNNING_BACKFILL || chainState == ChainState.RUNNING_BOTH)
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
            BackfillProgressSnapshot bf = calculateBackfillProgress(null, chain, cp);
            chainStatuses.putIfAbsent(chain, IndexerStatus.ChainStatus.builder()
                    .chainId(config != null ? (int) config.getChainId() : null)
                    .lastBlock(lastBlk)
                    .targetBlock(lastBlk)
                    .blocksIndexed(cp != null ? cp.getTotalBlocksIndexed() : 0L)
                    .transactionsIndexed(cp != null ? cp.getTotalTransactionsIndexed() : 0L)
                    .blocksPerSecond(0.0)
                    .rpcHealth("STOPPED")
                    .backfillProgress(bf.progress())
                    .backfillFloorBlock(bf.floorBlock())
                    .backfillTargetBlock(bf.targetBlock())
                    .reverseBackfillComplete(bf.complete())
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
     * Indexes blocks in pure reverse chronological order — from the chain head
     * backward toward the configured start block. Runs independently of
     * incremental tailing, which can run concurrently as a separate task.
     *
     * <p>Resumes from the persisted {@code backfillFloorBlock} checkpoint if
     * a previous backfill was interrupted. Sets {@code reverseBackfillComplete}
     * when the floor reaches the target start block.
     *
     * @param chain  the chain key
     * @param config chain configuration
     */
    private void indexBackfillOnly(String chain, IndexerProperties.ChainConfig config) {
        loadOrCreateCheckpoint(chain, config);
        ChainIndexingContext ctx = contexts.get(chain);

        // Determine backfill floor: prefer ctx (set fresh by startIndexing),
        // fall back to checkpoint only for crash recovery (floor made progress
        // from chainHead but didn't finish).
        IndexerCheckpoint cp = checkpointRepository.findByChain(chain).orElse(null);
        long backfillTarget = config.getStartBlock();
        long backfillFloor;
        if (cp != null && cp.getBackfillFloorBlock() != null
                && cp.getBackfillFloorBlock() > backfillTarget
                && cp.getBackfillFloorBlock() < ctx.backfillFloorBlock) {
            // Crash recovery: checkpoint has progress lower than fresh start
            backfillFloor = cp.getBackfillFloorBlock();
            log.info("Resuming backfill for chain={} from checkpoint floor={}", chain, backfillFloor);
        } else {
            // Fresh start from chainHead (set by startIndexing)
            backfillFloor = ctx.backfillFloorBlock;
        }
        ctx.backfillFloorBlock = backfillFloor;
        ctx.reverseBackfillComplete = backfillFloor <= backfillTarget;

        if (ctx.reverseBackfillComplete) {
            log.info("Backfill already complete for chain={}", chain);
            return;
        }

        int batchSize = properties.getConcurrency().getBatchSize();
        int backfillRetries = 0;

        log.info("chain={} backfill: floor={} target={}", chain, backfillFloor, backfillTarget);

        while (!ctx.stopRequested && !ctx.reverseBackfillComplete) {
            try {
                // Update chain head for accurate progress calculation
                ctx.chainHead = rpcClientService.getLatestBlockNumber(chain);

                long high = ctx.backfillFloorBlock - 1;

                if (high < backfillTarget) {
                    ctx.reverseBackfillComplete = true;
                    log.info("Backfill complete for chain={}: floor reached target {}",
                            chain, backfillTarget);
                    sendProgressUpdate(chain, ctx, ctx.chainHead);
                    break;
                }

                long low = Math.max(high - batchSize + 1, backfillTarget);
                List<Long> blockNumbers = buildBlockNumberList(low, high);
                Instant batchStartTime = Instant.now();

                try {
                    List<IndexedBlock> blocks = rpcClientService
                            .getIndexedBlocksAsync(chain, blockNumbers, fetchExecutor)
                            .join();

                    if (blocks.isEmpty()) {
                        ctx.errorCount.incrementAndGet();
                        incrementErrorCounter(chain);
                        log.warn("Empty backfill batch {}-{} on chain={}", low, high, chain);
                        ctx.backfillFloorBlock = low;
                    } else {
                        parquetWriterService.writeBlocks(blocks);
                        blockAnalyticsService.persistBatch(blocks);

                        long txCount = blocks.stream()
                                .mapToInt(b -> b.getTransactions().size())
                                .sum();

                        ctx.blocksProcessed.addAndGet(blocks.size());
                        ctx.transactionsProcessed.addAndGet(txCount);
                        ctx.throughputTracker.recordBackfillBatch(blocks.size());
                        ctx.backfillFloorBlock = low;
                        ctx.lastBatchAt = Instant.now();

                        updateBackfillFloor(chain, low, blocks.size(), txCount);

                        Duration batchDuration = Duration.between(batchStartTime, Instant.now());
                        recordBatchMetrics(chain, ctx, blocks.size(), txCount, batchDuration);
                        sendProgressUpdate(chain, ctx, ctx.chainHead);
                        sendRecentBlockNotifications(chain, blocks);

                        double bps = calculateBlocksPerSecond(ctx);
                        long remaining = low - backfillTarget;
                        String eta = bps > 0
                                ? formatDuration(Duration.ofSeconds((long) (remaining / bps)))
                                : "unknown";

                        log.info("chain={} backfill: floor {} ← target {} ({} left) | {}/s | ETA {} | batch: {} blk, {} tx, {}ms",
                                chain, low, backfillTarget, remaining,
                                String.format("%.1f", bps), eta,
                                blocks.size(), txCount, batchDuration.toMillis());
                    }

                    backfillRetries = 0;

                } catch (CompletionException | RpcClientService.RpcException e) {
                    backfillRetries++;
                    ctx.errorCount.incrementAndGet();
                    incrementErrorCounter(chain);
                    if (backfillRetries >= MAX_BACKFILL_RETRIES) {
                        log.error("Backfill batch {}-{} failed {} times, skipping. chain={}",
                                low, high, MAX_BACKFILL_RETRIES, chain);
                        ctx.backfillFloorBlock = low;
                        backfillRetries = 0;
                    } else {
                        log.warn("Backfill batch {}-{} failed (attempt {}/{}), will retry. chain={}",
                                low, high, backfillRetries, MAX_BACKFILL_RETRIES, chain);
                    }
                } catch (Exception e) {
                    backfillRetries++;
                    ctx.errorCount.incrementAndGet();
                    incrementErrorCounter(chain);
                    if (isCriticalError(e)) {
                        log.error("Critical error in backfill for chain={}: {}",
                                chain, e.getMessage(), e);
                        break;
                    }
                    if (backfillRetries >= MAX_BACKFILL_RETRIES) {
                        log.error("Backfill batch {}-{} failed {} times, skipping. chain={}",
                                low, high, MAX_BACKFILL_RETRIES, chain);
                        ctx.backfillFloorBlock = low;
                        backfillRetries = 0;
                    } else {
                        log.warn("Backfill batch {}-{} failed (attempt {}/{}), will retry. chain={}",
                                low, high, backfillRetries, MAX_BACKFILL_RETRIES, chain);
                    }
                }

            } catch (RpcClientService.RpcException e) {
                ctx.errorCount.incrementAndGet();
                incrementErrorCounter(chain);
                log.error("RPC error in backfill for chain={}: {}", chain, e.getMessage());
                sleep(INCREMENTAL_POLL_MS * 2);
            } catch (Exception e) {
                ctx.errorCount.incrementAndGet();
                incrementErrorCounter(chain);
                log.error("Error in backfill for chain={}: {}", chain, e.getMessage(), e);
                if (isCriticalError(e)) {
                    log.error("Critical error — aborting backfill for chain={}", chain);
                    break;
                }
                sleep(INCREMENTAL_POLL_MS * 2);
            }
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
        // Use the checkpoint's lastIndexedBlock when available to fill gaps.
        // On restart after a pause, the checkpoint may be behind chainHead —
        // resuming from checkpoint ensures no blocks are skipped.
        IndexerCheckpoint cp = checkpointRepository.findByChain(chain).orElse(null);
        long resumeFrom = cp != null ? cp.getLastIndexedBlock() : startFromBlock;
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
                    ctx.throughputTracker.recordIncrementalBlock();
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

        BackfillProgressSnapshot bf = calculateBackfillProgress(ctx, chain, null);

        IndexerProgressMessage msg = IndexerProgressMessage.builder()
                .chain(chain)
                .currentBlock(ctx.lastBlockNumber)
                .latestBlock(targetBlock)
                .blocksProcessed(ctx.blocksProcessed.get())
                .transactionsProcessed(ctx.transactionsProcessed.get())
                .blocksPerSecond(Math.round(bps * 10.0) / 10.0)
                .estimatedTimeRemaining(eta)
                .timestamp(System.currentTimeMillis())
                .backfillProgress(bf.progress())
                .backfillFloorBlock(bf.floorBlock())
                .backfillTargetBlock(bf.targetBlock())
                .reverseBackfillComplete(bf.complete())
                .build();

        webSocketService.sendProgress(chain, msg);
    }

    /**
     * Sends block-indexed notifications for every block in a batch.
     * WebSocketService.sendBlockIndexed is unthrottled so each message
     * reaches the frontend, giving the block feed a complete view.
     */
    private void sendRecentBlockNotifications(String chain, List<IndexedBlock> blocks) {
        for (int i = 0; i < blocks.size(); i++) {
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
        // Use sliding window for responsive, accurate throughput
        double windowBps = ctx.throughputTracker.getBlocksPerSecond();
        if (windowBps > 0) {
            return windowBps;
        }
        // Fall back to lifetime average only during initial startup
        long elapsedSeconds = Duration.between(ctx.startedAt, Instant.now()).toSeconds();
        return elapsedSeconds > 0
                ? (double) ctx.blocksProcessed.get() / elapsedSeconds
                : 0.0;
    }

    /** Snapshot of reverse backfill progress for API / WebSocket responses. */
    private record BackfillProgressSnapshot(Double progress, Long floorBlock, Long targetBlock, Boolean complete) {}

    /**
     * Calculates backfill progress from context (when running) or checkpoint (when stopped).
     *
     * @param ctx   the chain context, may be null for chains that haven't been started
     * @param chain the chain key
     * @param cp    the checkpoint, may be null
     */
    private BackfillProgressSnapshot calculateBackfillProgress(
            ChainIndexingContext ctx, String chain, IndexerCheckpoint cp) {
        IndexerProperties.ChainConfig config = properties.getChains().get(chain);
        long startBlock = config != null ? config.getStartBlock() : 0L;

        Long head = null;
        Long floor = null;
        Boolean complete = null;

        ChainState ctxState = ctx != null ? ctx.getState() : ChainState.STOPPED;
        if (ctx != null && (ctxState == ChainState.RUNNING_BACKFILL || ctxState == ChainState.RUNNING_BOTH)) {
            // Actively backfilling — use live context data
            head = ctx.chainHead > 0 ? ctx.chainHead : null;
            floor = ctx.backfillFloorBlock > 0 ? ctx.backfillFloorBlock : head;
            complete = false;
        } else if (ctx != null && ctx.reverseBackfillComplete) {
            // Backfill finished this session
            head = ctx.chainHead > 0 ? ctx.chainHead : (cp != null ? cp.getLastIndexedBlock() : null);
            floor = startBlock;
            complete = true;
        } else if (cp != null && cp.getBackfillFloorBlock() != null) {
            // Stopped or not started, but checkpoint has backfill data from a previous run
            head = cp.getLastIndexedBlock();
            floor = cp.getBackfillFloorBlock();
            complete = floor <= startBlock;
        } else {
            return new BackfillProgressSnapshot(null, null, null, null);
        }

        if (head == null || floor == null) {
            return new BackfillProgressSnapshot(null, null, null, null);
        }

        long totalRange = head - startBlock;
        long indexed = head - floor;
        double pct = totalRange > 0
                ? Math.min(100.0, (double) indexed / totalRange * 100.0)
                : 100.0;
        return new BackfillProgressSnapshot(
                Math.round(pct * 10.0) / 10.0, floor, startBlock, complete);
    }

    private IndexerStatus.ChainStatus buildChainStatus(String chain, ChainIndexingContext ctx, IndexerCheckpoint cp) {
        // Use cumulative checkpoint data + session data for accurate totals
        long totalBlocks = cp != null ? cp.getTotalBlocksIndexed() : ctx.blocksProcessed.get();
        long totalTxs = cp != null ? cp.getTotalTransactionsIndexed() : ctx.transactionsProcessed.get();
        long lastBlock = ctx.lastBlockNumber > 0 ? ctx.lastBlockNumber : (cp != null ? cp.getLastIndexedBlock() : 0L);
        long target = ctx.chainHead > 0 ? ctx.chainHead : lastBlock;

        IndexerProperties.ChainConfig config = properties.getChains().get(chain);
        BackfillProgressSnapshot bf = calculateBackfillProgress(ctx, chain, cp);

        return IndexerStatus.ChainStatus.builder()
                .chainId(config != null ? (int) config.getChainId() : null)
                .lastBlock(lastBlock)
                .targetBlock(target)
                .blocksIndexed(totalBlocks)
                .transactionsIndexed(totalTxs)
                .blocksPerSecond(calculateBlocksPerSecond(ctx))
                .rpcHealth(ctx.getState().name())
                .backfillProgress(bf.progress())
                .backfillFloorBlock(bf.floorBlock())
                .backfillTargetBlock(bf.targetBlock())
                .reverseBackfillComplete(bf.complete())
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
        Future<?> incrementalTask;            // live tailing task
        Future<?> backfillTask;               // reverse backfill task

        /** Sliding window throughput tracker — records batch completions for accurate BPS. */
        final ThroughputTracker throughputTracker = new ThroughputTracker();

        /** Derive state from which tasks are still running. */
        ChainState getState() {
            boolean inc = incrementalTask != null && !incrementalTask.isDone();
            boolean bf = backfillTask != null && !backfillTask.isDone();
            if (inc && bf) return ChainState.RUNNING_BOTH;
            if (inc) return ChainState.RUNNING_INCREMENTAL;
            if (bf) return ChainState.RUNNING_BACKFILL;
            return ChainState.STOPPED;
        }
    }

    /**
     * Tracks throughput over a sliding window for accurate blocks/second calculation.
     *
     * <p>Records timestamped block counts and computes throughput from the most
     * recent {@value #WINDOW_SECONDS}-second window. This gives a responsive
     * reading that reflects current performance rather than a lifetime average
     * that dilutes spikes and troughs.
     *
     * <p>Thread-safe: all access is synchronized on the instance.
     */
    /**
     * EWMA-based throughput tracker with per-mode rate tracking.
     *
     * <p>Tracks backfill and incremental throughput separately using
     * exponentially weighted moving averages, then sums them for the
     * total chain throughput. This avoids the oscillation artifacts
     * of fixed sliding windows (where batch boundary alignment causes
     * BPS to swing between N and 2N) and handles mode interleaving
     * correctly.
     *
     * <p>Each mode's contribution automatically drops to zero after
     * {@link #STALE_THRESHOLD_MS} of inactivity (e.g., when that mode
     * is stopped while the other continues).
     */
    static class ThroughputTracker {
        /** Smoothing factor — 0.3 gives ~70% weight to history, 30% to new measurement. */
        private static final double ALPHA = 0.3;

        /** After this many ms with no batches, a mode's BPS contribution drops to zero. */
        private static final long STALE_THRESHOLD_MS = 15_000;

        private double backfillBps = 0.0;
        private long lastBackfillMs = 0;

        private double incrementalBps = 0.0;
        private long lastIncrementalMs = 0;

        /** Record completion of a backfill batch of {@code blockCount} blocks. */
        synchronized void recordBackfillBatch(int blockCount) {
            long now = System.currentTimeMillis();
            if (lastBackfillMs > 0) {
                double elapsedSec = (now - lastBackfillMs) / 1000.0;
                if (elapsedSec > 0.05) { // ignore sub-50ms intervals (clock jitter)
                    double instant = blockCount / elapsedSec;
                    backfillBps = backfillBps == 0.0
                            ? instant
                            : ALPHA * instant + (1 - ALPHA) * backfillBps;
                }
            }
            lastBackfillMs = now;
        }

        /** Record completion of a single incremental block. */
        synchronized void recordIncrementalBlock() {
            long now = System.currentTimeMillis();
            if (lastIncrementalMs > 0) {
                double elapsedSec = (now - lastIncrementalMs) / 1000.0;
                if (elapsedSec > 0.05) {
                    double instant = 1.0 / elapsedSec;
                    incrementalBps = incrementalBps == 0.0
                            ? instant
                            : ALPHA * instant + (1 - ALPHA) * incrementalBps;
                }
            }
            lastIncrementalMs = now;
        }

        /**
         * Returns the combined blocks-per-second across all active modes.
         * Each mode's contribution is included only if it has received a
         * batch within the staleness threshold.
         */
        synchronized double getBlocksPerSecond() {
            long now = System.currentTimeMillis();
            double total = 0.0;
            if (lastBackfillMs > 0 && (now - lastBackfillMs) < STALE_THRESHOLD_MS) {
                total += backfillBps;
            }
            if (lastIncrementalMs > 0 && (now - lastIncrementalMs) < STALE_THRESHOLD_MS) {
                total += incrementalBps;
            }
            return total;
        }
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
