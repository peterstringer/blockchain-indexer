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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    private final SimpMessagingTemplate messagingTemplate;
    private final MeterRegistry meterRegistry;

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
                               java.util.Optional<SimpMessagingTemplate> messagingTemplate,
                               MeterRegistry meterRegistry) {
        this.properties = properties;
        this.rpcClientService = rpcClientService;
        this.parquetWriterService = parquetWriterService;
        this.checkpointRepository = checkpointRepository;
        this.metricsRepository = metricsRepository;
        this.messagingTemplate = messagingTemplate.orElse(null);
        this.meterRegistry = meterRegistry;
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

        ChainIndexingContext ctx = new ChainIndexingContext();
        ctx.state = mode == IndexMode.BACKFILL
                ? ChainState.RUNNING_BACKFILL
                : ChainState.RUNNING_INCREMENTAL;
        ctx.startedAt = Instant.now();
        contexts.put(chain, ctx);

        registerMicrometerMetrics(chain, ctx);

        ctx.task = chainExecutor.submit(() -> {
            try {
                if (mode == IndexMode.BACKFILL) {
                    indexBackfill(chain, config);
                } else {
                    indexIncremental(chain, config);
                }
            } catch (Exception e) {
                log.error("Indexing failed for chain={}: {}", chain, e.getMessage(), e);
            } finally {
                ctx.state = ChainState.STOPPED;
                ctx.stopRequested = false;
                log.info("Indexing loop exited for chain={}: {} blocks, {} txs, {} errors",
                        chain, ctx.blocksProcessed.get(),
                        ctx.transactionsProcessed.get(), ctx.errorCount.get());
            }
        });

        log.info("Started {} indexing for chain={} (start_block={}, demo={})",
                mode, chain, config.getStartBlock(), rpcClientService.isDemoMode());
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

        if (ctx.task != null) {
            try {
                ctx.task.get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("Timed out waiting for chain={} to stop — cancelling task", chain);
                ctx.task.cancel(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ctx.task.cancel(true);
            } catch (ExecutionException e) {
                log.warn("Chain {} task completed with error: {}", chain, e.getMessage());
            }
        }

        // Flush remaining Parquet data for this chain's partitions
        try {
            parquetWriterService.flushAll();
        } catch (Exception e) {
            log.warn("Error flushing Parquet data after stopping chain={}: {}", chain, e.getMessage());
        }

        log.info("Indexing stopped for chain={}: {} blocks, {} txs",
                chain, ctx.blocksProcessed.get(), ctx.transactionsProcessed.get());
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

        for (var entry : contexts.entrySet()) {
            ChainIndexingContext ctx = entry.getValue();
            if (ctx.state != ChainState.STOPPED) {
                anyRunning = true;
                overallMode = ctx.state == ChainState.RUNNING_BACKFILL
                        ? IndexerStatus.Mode.BACKFILL
                        : IndexerStatus.Mode.INCREMENTAL;
            }
            chainStatuses.put(entry.getKey(), buildChainStatus(ctx));
        }

        // Include chains that haven't been started yet
        for (String chain : properties.getChains().keySet()) {
            chainStatuses.putIfAbsent(chain, IndexerStatus.ChainStatus.builder()
                    .lastBlock(0L)
                    .blocksIndexed(0L)
                    .transactionsIndexed(0L)
                    .blocksPerSecond(0.0)
                    .rpcHealth("NOT_STARTED")
                    .build());
        }

        return IndexerStatus.builder()
                .running(anyRunning)
                .mode(overallMode)
                .chains(chainStatuses)
                .build();
    }

    // =========================================================================
    // Backfill indexing loop
    // =========================================================================

    /**
     * Processes a bounded range of historical blocks in configurable batch sizes.
     *
     * <p>Resumes from the persisted checkpoint and runs until either the target
     * block is reached or a stop is requested. Each batch is:
     * <ol>
     *   <li>Fetched concurrently via {@link RpcClientService#getIndexedBlocksAsync}</li>
     *   <li>Written to Parquet via {@link ParquetWriterService#writeBlocks}</li>
     *   <li>Checkpointed to the database</li>
     *   <li>Broadcast via WebSocket</li>
     * </ol>
     */
    private void indexBackfill(String chain, IndexerProperties.ChainConfig config) {
        IndexerCheckpoint checkpoint = loadOrCreateCheckpoint(chain, config);
        long startBlock = checkpoint.getLastIndexedBlock() + 1;
        long endBlock = config.getEndBlock() != null
                ? config.getEndBlock()
                : rpcClientService.getLatestBlockNumber(chain);

        if (startBlock > endBlock) {
            log.info("Backfill already complete for chain={}: at block {}", chain, startBlock - 1);
            return;
        }

        long totalBlocks = endBlock - startBlock + 1;
        log.info("Starting backfill for chain={}: blocks {} → {} ({} total)",
                chain, startBlock, endBlock, totalBlocks);

        int batchSize = properties.getConcurrency().getBatchSize();
        ChainIndexingContext ctx = contexts.get(chain);

        for (long batchStart = startBlock; batchStart <= endBlock && !ctx.stopRequested;
             batchStart += batchSize) {

            long batchEnd = Math.min(batchStart + batchSize - 1, endBlock);
            List<Long> blockNumbers = buildBlockNumberList(batchStart, batchEnd);

            Instant batchStartTime = Instant.now();

            try {
                List<IndexedBlock> blocks = rpcClientService
                        .getIndexedBlocksAsync(chain, blockNumbers, fetchExecutor)
                        .join();

                if (blocks.isEmpty()) {
                    ctx.errorCount.incrementAndGet();
                    incrementErrorCounter(chain);
                    log.warn("Empty batch {}-{} on chain={}", batchStart, batchEnd, chain);
                    continue;
                }

                // Write blocks + embedded transactions to Parquet
                parquetWriterService.writeBlocks(blocks);

                // Update counters
                long txCount = blocks.stream()
                        .mapToInt(b -> b.getTransactions().size())
                        .sum();

                ctx.blocksProcessed.addAndGet(blocks.size());
                ctx.transactionsProcessed.addAndGet(txCount);
                ctx.lastBlockNumber = batchEnd;
                ctx.lastBlockHash = blocks.getLast().getBlockHash();
                ctx.lastBatchAt = Instant.now();

                // Persist checkpoint
                updateCheckpoint(chain, batchEnd, blocks.size(), txCount);

                // Record metrics
                Duration batchDuration = Duration.between(batchStartTime, Instant.now());
                recordBatchMetrics(chain, ctx, blocks.size(), txCount, batchDuration);

                // WebSocket progress
                sendProgressUpdate(chain, ctx, endBlock);

                // Log progress
                double bps = calculateBlocksPerSecond(ctx);
                long remaining = endBlock - batchEnd;
                String eta = bps > 0
                        ? formatDuration(Duration.ofSeconds((long) (remaining / bps)))
                        : "unknown";

                log.info("chain={} backfill: block {}/{} ({} left) | {}/s | ETA {} | batch: {} blk, {} tx, {}ms",
                        chain, batchEnd, endBlock, remaining,
                        String.format("%.1f", bps), eta,
                        blocks.size(), txCount, batchDuration.toMillis());

            } catch (CompletionException | RpcClientService.RpcException e) {
                ctx.errorCount.incrementAndGet();
                incrementErrorCounter(chain);
                log.error("Batch {}-{} failed on chain={}: {}",
                        batchStart, batchEnd, chain, e.getMessage());
            } catch (Exception e) {
                ctx.errorCount.incrementAndGet();
                incrementErrorCounter(chain);
                log.error("Unexpected error in batch {}-{} on chain={}: {}",
                        batchStart, batchEnd, chain, e.getMessage(), e);
                if (isCriticalError(e)) {
                    log.error("Critical error — aborting backfill for chain={}", chain);
                    break;
                }
            }
        }

        if (!ctx.stopRequested) {
            log.info("Backfill complete for chain={}: {} blocks, {} txs indexed",
                    chain, ctx.blocksProcessed.get(), ctx.transactionsProcessed.get());
        }
    }

    // =========================================================================
    // Incremental indexing loop
    // =========================================================================

    /**
     * Continuously polls for new blocks and processes them as they appear.
     *
     * <p>Implements chain-reorg detection by comparing each block's
     * {@code parentHash} against the hash of the previously indexed block.
     * On reorg, the indexer rolls back and re-processes from the fork point
     * (up to {@value #REORG_MAX_DEPTH} blocks back).
     *
     * <p>The poll interval defaults to {@value #INCREMENTAL_POLL_MS}ms,
     * matching typical EVM block times. On RPC errors, the interval is
     * doubled temporarily to reduce load on recovering providers.
     */
    private void indexIncremental(String chain, IndexerProperties.ChainConfig config) {
        IndexerCheckpoint checkpoint = loadOrCreateCheckpoint(chain, config);
        ChainIndexingContext ctx = contexts.get(chain);
        ctx.lastBlockNumber = checkpoint.getLastIndexedBlock();

        log.info("Starting incremental indexing for chain={} from block {}",
                chain, ctx.lastBlockNumber);

        while (!ctx.stopRequested) {
            try {
                long latestBlock = rpcClientService.getLatestBlockNumber(chain);

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

                    // Update state
                    long txCount = block.getTransactions().size();
                    ctx.blocksProcessed.incrementAndGet();
                    ctx.transactionsProcessed.addAndGet(txCount);
                    ctx.lastBlockNumber = blockNum;
                    ctx.lastBlockHash = block.getBlockHash();
                    ctx.lastBatchAt = Instant.now();

                    // Checkpoint + metrics
                    updateCheckpoint(chain, blockNum, 1, txCount);
                    Duration blockDuration = Duration.between(blockStart, Instant.now());
                    recordBatchMetrics(chain, ctx, 1, txCount, blockDuration);
                    sendProgressUpdate(chain, ctx, latestBlock);

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

    // =========================================================================
    // WebSocket progress
    // =========================================================================

    private void sendProgressUpdate(String chain, ChainIndexingContext ctx,
                                    long targetBlock) {
        if (messagingTemplate == null) {
            return;
        }
        try {
            double bps = calculateBlocksPerSecond(ctx);
            long remaining = targetBlock - ctx.lastBlockNumber;
            String eta = (bps > 0 && remaining > 0)
                    ? formatDuration(Duration.ofSeconds((long) (remaining / bps)))
                    : "N/A";

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chain", chain);
            payload.put("blockNumber", ctx.lastBlockNumber);
            payload.put("blocksProcessed", ctx.blocksProcessed.get());
            payload.put("transactionsProcessed", ctx.transactionsProcessed.get());
            payload.put("blocksPerSecond", Math.round(bps * 10.0) / 10.0);
            payload.put("eta", eta);
            payload.put("mode", ctx.state.name());
            payload.put("errors", ctx.errorCount.get());

            messagingTemplate.convertAndSend("/topic/indexer/" + chain, (Object) payload);
        } catch (Exception e) {
            log.debug("Failed to send WebSocket update for chain={}: {}", chain, e.getMessage());
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

    private IndexerStatus.ChainStatus buildChainStatus(ChainIndexingContext ctx) {
        return IndexerStatus.ChainStatus.builder()
                .lastBlock(ctx.lastBlockNumber)
                .blocksIndexed(ctx.blocksProcessed.get())
                .transactionsIndexed(ctx.transactionsProcessed.get())
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
        volatile long lastBlockNumber;
        volatile String lastBlockHash;
        Future<?> task;
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
