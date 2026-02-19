package com.peterstringer.blockchain.indexer.controller;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.dto.ErrorResponse;
import com.peterstringer.blockchain.indexer.dto.StartIndexingRequest;
import com.peterstringer.blockchain.indexer.dto.StopIndexingRequest;
import com.peterstringer.blockchain.indexer.exception.ChainNotFoundException;
import com.peterstringer.blockchain.indexer.exception.IndexerAlreadyRunningException;
import com.peterstringer.blockchain.indexer.model.IndexerCheckpoint;
import com.peterstringer.blockchain.indexer.model.IndexerMetric;
import com.peterstringer.blockchain.indexer.model.IndexerStatus;
import com.peterstringer.blockchain.indexer.repository.CheckpointRepository;
import com.peterstringer.blockchain.indexer.repository.MetricsRepository;
import com.peterstringer.blockchain.indexer.service.BlockIndexerService;
import com.peterstringer.blockchain.indexer.service.RpcClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing the blockchain indexing pipeline.
 *
 * <p>Exposes endpoints to start/stop indexing, query status, manage
 * checkpoints, and retrieve operational metrics. All responses use
 * standard HTTP status codes and consistent JSON structures.
 *
 * <p>Base path: {@code /api/indexer}
 *
 * @see BlockIndexerService
 */
@RestController
@RequestMapping("/api/indexer")
@Tag(name = "Indexer", description = "Control and monitor the blockchain indexing pipeline")
public class IndexerController {

    private static final Logger log = LoggerFactory.getLogger(IndexerController.class);

    private final BlockIndexerService indexerService;
    private final CheckpointRepository checkpointRepository;
    private final MetricsRepository metricsRepository;
    private final IndexerProperties properties;
    private final RpcClientService rpcClientService;

    public IndexerController(BlockIndexerService indexerService,
                             CheckpointRepository checkpointRepository,
                             MetricsRepository metricsRepository,
                             IndexerProperties properties,
                             RpcClientService rpcClientService) {
        this.indexerService = indexerService;
        this.checkpointRepository = checkpointRepository;
        this.metricsRepository = metricsRepository;
        this.properties = properties;
        this.rpcClientService = rpcClientService;
    }

    // =========================================================================
    // POST /start
    // =========================================================================

    /**
     * Starts indexing for a specific chain.
     *
     * <p>The chain must be configured in {@code application.yml} and must not
     * already be running. The indexer starts asynchronously — this endpoint
     * returns immediately after submitting the task.
     *
     * @param request the chain and mode to start
     * @return confirmation with chain name and selected mode
     */
    @Operation(summary = "Start indexing for a chain",
            description = "Begins indexing in BACKFILL or INCREMENTAL mode. Returns immediately; indexing runs asynchronously.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Indexing started successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request body or unknown chain",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Chain is already being indexed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startIndexing(
            @Valid @RequestBody StartIndexingRequest request) {

        try {
            indexerService.startIndexing(request.getChain(), request.getMode());
        } catch (IllegalArgumentException e) {
            throw new ChainNotFoundException(request.getChain());
        } catch (IllegalStateException e) {
            throw new IndexerAlreadyRunningException(request.getChain());
        }

        log.info("API: started {} indexing for chain={}", request.getMode(), request.getChain());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "started");
        response.put("chain", request.getChain());
        response.put("mode", request.getMode().name());
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // POST /stop
    // =========================================================================

    /**
     * Stops indexing for a specific chain or all chains.
     *
     * <p>If the request body is empty or {@code chain} is null/blank, all
     * running chains are stopped. The endpoint waits for the current batch
     * to complete before returning.
     *
     * @param request optional body specifying the chain to stop
     * @return confirmation with the chain(s) stopped
     */
    @Operation(summary = "Stop indexing",
            description = "Gracefully stops indexing for a specific chain, or all chains if no chain is specified.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Indexing stopped successfully"),
            @ApiResponse(responseCode = "404", description = "Specified chain not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopIndexing(
            @RequestBody(required = false) StopIndexingRequest request) {

        if (request == null || request.getChain() == null || request.getChain().isBlank()) {
            indexerService.stopAll();
            log.info("API: stopped all chains");
            return ResponseEntity.ok(Map.of("status", "stopped", "chain", "all"));
        }

        validateChainExists(request.getChain());
        indexerService.stopIndexing(request.getChain());
        log.info("API: stopped indexing for chain={}", request.getChain());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "stopped");
        response.put("chain", request.getChain());
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // GET /status
    // =========================================================================

    /**
     * Returns comprehensive indexer status across all configured chains.
     *
     * <p>Includes running state, current mode, and per-chain progress with
     * throughput metrics and RPC health.
     *
     * @return full indexer status snapshot
     */
    @Operation(summary = "Get indexer status",
            description = "Returns running state, mode, and per-chain progress for all configured chains.")
    @ApiResponse(responseCode = "200", description = "Status retrieved successfully")
    @GetMapping("/status")
    public ResponseEntity<IndexerStatus> getStatus() {
        return ResponseEntity.ok(indexerService.getStatus());
    }

    // =========================================================================
    // GET /status/{chain}
    // =========================================================================

    /**
     * Returns the status for a specific chain.
     *
     * @param chain the chain key (e.g. "ethereum")
     * @return per-chain status snapshot
     */
    @Operation(summary = "Get status for a specific chain",
            description = "Returns progress, throughput, and RPC health for the specified chain.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Chain status retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Chain not configured",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/status/{chain}")
    public ResponseEntity<IndexerStatus.ChainStatus> getChainStatus(
            @Parameter(description = "Chain key (e.g. ethereum, polygon)")
            @PathVariable String chain) {

        validateChainExists(chain);

        IndexerStatus status = indexerService.getStatus();
        IndexerStatus.ChainStatus chainStatus = status.getChains().get(chain);
        return ResponseEntity.ok(chainStatus);
    }

    // =========================================================================
    // GET /health
    // =========================================================================

    /**
     * Simple health check for Docker/Kubernetes liveness probes.
     *
     * <p>Returns a minimal payload indicating the application is responsive.
     * For deeper health inspection, use the Spring Actuator {@code /actuator/health}
     * endpoint.
     *
     * @return health status with timestamp
     */
    @Operation(summary = "Health check",
            description = "Lightweight liveness probe for container orchestrators. Always returns 200 if the application is up.")
    @ApiResponse(responseCode = "200", description = "Application is healthy")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "healthy");
        response.put("timestamp", System.currentTimeMillis());
        response.put("demoMode", rpcClientService.isDemoMode());

        IndexerStatus status = indexerService.getStatus();
        response.put("running", status.isRunning());
        response.put("chainsConfigured", properties.getChains().size());

        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // GET /checkpoints
    // =========================================================================

    /**
     * Returns all persisted checkpoints ordered by last indexed block.
     *
     * <p>Each checkpoint shows the chain name, last indexed block number,
     * cumulative block/transaction counts, and timestamps.
     *
     * @return all checkpoints
     */
    @Operation(summary = "List all checkpoints",
            description = "Returns per-chain checkpoint data including last indexed block and cumulative counts.")
    @ApiResponse(responseCode = "200", description = "Checkpoints retrieved successfully")
    @GetMapping("/checkpoints")
    public ResponseEntity<List<IndexerCheckpoint>> getCheckpoints() {
        return ResponseEntity.ok(checkpointRepository.findAllOrderByLastIndexedBlockDesc());
    }

    // =========================================================================
    // POST /checkpoints/{chain}/reset
    // =========================================================================

    /**
     * Resets the checkpoint for a chain back to its configured start block.
     *
     * <p>This is a destructive operation: the indexer will re-process all
     * blocks from the beginning on the next start. Requires the query
     * parameter {@code confirm=true} as a safety guard.
     *
     * <p>The chain must not be actively running when the checkpoint is reset.
     *
     * @param chain   the chain key
     * @param confirm must be {@code true} to proceed
     * @return confirmation with the new start block
     */
    @Operation(summary = "Reset checkpoint for a chain",
            description = "Deletes the checkpoint, causing re-indexing from the configured start block on next run. Requires ?confirm=true.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Checkpoint reset successfully"),
            @ApiResponse(responseCode = "400", description = "Missing confirmation parameter",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Chain not configured",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Cannot reset while chain is running",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/checkpoints/{chain}/reset")
    public ResponseEntity<Map<String, Object>> resetCheckpoint(
            @Parameter(description = "Chain key (e.g. ethereum)")
            @PathVariable String chain,
            @Parameter(description = "Safety confirmation flag — must be true")
            @RequestParam(defaultValue = "false") boolean confirm) {

        IndexerProperties.ChainConfig config = properties.getChains().get(chain);
        if (config == null) {
            throw new ChainNotFoundException(chain);
        }

        if (!confirm) {
            Map<String, Object> warning = new LinkedHashMap<>();
            warning.put("error", "Checkpoint reset requires confirmation");
            warning.put("message", "Add ?confirm=true to confirm this destructive operation");
            warning.put("chain", chain);
            return ResponseEntity.badRequest().body(warning);
        }

        // Prevent reset while the chain is actively indexing
        IndexerStatus status = indexerService.getStatus();
        IndexerStatus.ChainStatus chainStatus = status.getChains().get(chain);
        if (chainStatus != null && isChainActive(chainStatus)) {
            throw new IndexerAlreadyRunningException(chain);
        }

        // Delete the existing checkpoint — a new one will be created on next start
        checkpointRepository.findByChain(chain).ifPresent(checkpointRepository::delete);

        log.info("API: reset checkpoint for chain={} — will re-index from block {}",
                chain, config.getStartBlock());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "reset");
        response.put("chain", chain);
        response.put("newStartBlock", config.getStartBlock());
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // GET /metrics
    // =========================================================================

    /**
     * Returns detailed operational metrics for all chains.
     *
     * <p>Includes current throughput, error rates, block progress, and RPC
     * provider health. For Micrometer/Prometheus-compatible metrics, use the
     * Spring Actuator endpoint {@code /actuator/prometheus}.
     *
     * @return per-chain metrics and RPC provider health
     */
    @Operation(summary = "Get operational metrics",
            description = "Returns per-chain throughput, error counts, progress, and RPC provider health.")
    @ApiResponse(responseCode = "200", description = "Metrics retrieved successfully")
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        IndexerStatus status = indexerService.getStatus();

        // Per-chain metrics from live indexer state
        Map<String, Object> chainMetrics = new LinkedHashMap<>();
        status.getChains().forEach((chain, cs) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("blocksPerSecond", cs.getBlocksPerSecond());
            m.put("blocksIndexed", cs.getBlocksIndexed());
            m.put("transactionsIndexed", cs.getTransactionsIndexed());
            m.put("currentBlock", cs.getLastBlock());
            m.put("rpcHealth", cs.getRpcHealth());

            // Add recent DB-persisted metrics for this chain
            OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
            List<IndexerMetric> recentMetrics = metricsRepository
                    .findByChainAndMetricNameAndRecordedAtAfterOrderByRecordedAtAsc(
                            chain, "blocks_per_second", since);
            if (!recentMetrics.isEmpty()) {
                m.put("avgBlocksPerSecond1h", recentMetrics.stream()
                        .mapToDouble(IndexerMetric::getMetricValue).average().orElse(0));
                m.put("peakBlocksPerSecond1h", recentMetrics.stream()
                        .mapToDouble(IndexerMetric::getMetricValue).max().orElse(0));
            }

            chainMetrics.put(chain, m);
        });
        result.put("chains", chainMetrics);

        // RPC provider health (production mode only)
        if (!rpcClientService.isDemoMode()) {
            result.put("rpcProviders", rpcClientService.getProviderHealth());
        }

        result.put("demoMode", rpcClientService.isDemoMode());
        result.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void validateChainExists(String chain) {
        if (!properties.getChains().containsKey(chain)) {
            throw new ChainNotFoundException(chain);
        }
    }

    private static boolean isChainActive(IndexerStatus.ChainStatus cs) {
        String health = cs.getRpcHealth();
        return health != null
                && !"STOPPED".equals(health)
                && !"NOT_STARTED".equals(health);
    }
}
