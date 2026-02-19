package com.peterstringer.blockchain.indexer.controller;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.dto.ErrorResponse;
import com.peterstringer.blockchain.indexer.dto.GasPriceAggregation;
import com.peterstringer.blockchain.indexer.exception.ChainNotFoundException;
import com.peterstringer.blockchain.indexer.model.IndexedBlock;
import com.peterstringer.blockchain.indexer.service.RpcClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * REST controller for querying indexed blockchain data.
 *
 * <p>Provides analytics endpoints for gas price aggregation, block lookups,
 * and transaction searches. Data is fetched live from the RPC layer (or
 * synthetic data in demo mode). For production-scale historical queries,
 * these endpoints should be backed by a Parquet query engine (e.g. DuckDB).
 *
 * <p>Base path: {@code /api/analytics}
 *
 * @see RpcClientService
 */
@RestController
@RequestMapping("/api/analytics")
@Tag(name = "Analytics", description = "Query indexed blockchain data for analysis and dashboards")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    /** Maximum blocks per gas-price aggregation request. */
    private static final int MAX_GAS_PRICE_BLOCKS = 1000;

    private final RpcClientService rpcClientService;
    private final IndexerProperties properties;

    public AnalyticsController(RpcClientService rpcClientService,
                               IndexerProperties properties) {
        this.rpcClientService = rpcClientService;
        this.properties = properties;
    }

    // =========================================================================
    // GET /gas-prices
    // =========================================================================

    /**
     * Returns aggregated gas price data for a range of blocks.
     *
     * <p>Fetches blocks via the RPC layer (or synthetic provider in demo mode)
     * and computes aggregate statistics. Limited to {@value #MAX_GAS_PRICE_BLOCKS}
     * blocks per request to prevent excessive RPC load.
     *
     * <p><b>Note:</b> For production-scale historical analytics over millions
     * of blocks, this endpoint should be backed by a Parquet query engine
     * (e.g. DuckDB) instead of live RPC calls.
     *
     * @param chain     chain key (e.g. "ethereum")
     * @param fromBlock start of the block range (inclusive)
     * @param toBlock   end of the block range (inclusive)
     * @return aggregated gas price data with per-block data points
     */
    @Operation(summary = "Get aggregated gas price data",
            description = """
                    Fetches blocks in the specified range and computes gas price statistics.
                    Limited to 1000 blocks per request. For larger historical ranges, a
                    Parquet-backed query engine is recommended.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Gas price data retrieved"),
            @ApiResponse(responseCode = "400", description = "Invalid parameters",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Chain not configured",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/gas-prices")
    public ResponseEntity<GasPriceAggregation> getGasPrices(
            @Parameter(description = "Chain key (e.g. ethereum)")
            @RequestParam String chain,
            @Parameter(description = "Start block number (inclusive)")
            @RequestParam long fromBlock,
            @Parameter(description = "End block number (inclusive)")
            @RequestParam long toBlock) {

        validateChainExists(chain);

        if (fromBlock > toBlock) {
            throw new IllegalArgumentException("fromBlock must be <= toBlock");
        }
        if (toBlock - fromBlock + 1 > MAX_GAS_PRICE_BLOCKS) {
            throw new IllegalArgumentException(
                    "Range too large: max %d blocks per request (requested %d)"
                            .formatted(MAX_GAS_PRICE_BLOCKS, toBlock - fromBlock + 1));
        }

        // Fetch blocks concurrently
        List<Long> blockNumbers = new ArrayList<>();
        for (long i = fromBlock; i <= toBlock; i++) {
            blockNumbers.add(i);
        }

        List<IndexedBlock> blocks;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            blocks = rpcClientService
                    .getIndexedBlocksAsync(chain, blockNumbers, executor)
                    .join();
        }

        // Build per-block data points
        List<GasPriceAggregation.BlockGasData> dataPoints = blocks.stream()
                .map(b -> GasPriceAggregation.BlockGasData.builder()
                        .blockNumber(b.getBlockNumber())
                        .timestamp(b.getTimestamp())
                        .baseFeePerGas(b.getBaseFeePerGas())
                        .avgGasPrice(b.getAvgGasPrice())
                        .gasUsedPercentage(b.getGasUsedPercentage())
                        .transactionCount(b.getTransactionCount())
                        .build())
                .toList();

        // Compute aggregates
        LongSummaryStatistics avgStats = blocks.stream()
                .filter(b -> b.getAvgGasPrice() != null)
                .mapToLong(IndexedBlock::getAvgGasPrice)
                .summaryStatistics();

        LongSummaryStatistics baseFeeStats = blocks.stream()
                .filter(b -> b.getBaseFeePerGas() != null)
                .mapToLong(IndexedBlock::getBaseFeePerGas)
                .summaryStatistics();

        double avgGasUtil = blocks.stream()
                .filter(b -> b.getGasUsedPercentage() != null)
                .mapToDouble(IndexedBlock::getGasUsedPercentage)
                .average()
                .orElse(0.0);

        GasPriceAggregation aggregation = GasPriceAggregation.builder()
                .chain(chain)
                .fromBlock(fromBlock)
                .toBlock(toBlock)
                .blocksAnalyzed(blocks.size())
                .avgBaseFee(baseFeeStats.getCount() > 0 ? (long) baseFeeStats.getAverage() : null)
                .avgGasPrice(avgStats.getCount() > 0 ? (long) avgStats.getAverage() : null)
                .maxGasPrice(avgStats.getCount() > 0 ? avgStats.getMax() : null)
                .minGasPrice(avgStats.getCount() > 0 ? avgStats.getMin() : null)
                .avgGasUtilization(Math.round(avgGasUtil * 10.0) / 10.0)
                .dataPoints(dataPoints)
                .build();

        return ResponseEntity.ok(aggregation);
    }

    // =========================================================================
    // GET /blocks/{chain}/{blockNumber}
    // =========================================================================

    /**
     * Returns detailed data for a specific block.
     *
     * <p>Fetches the block from the RPC provider (or synthetic data in demo
     * mode). Includes gas statistics, miner, difficulty, and all header fields.
     *
     * @param chain       chain key (e.g. "ethereum")
     * @param blockNumber the block number to retrieve
     * @return the indexed block data
     */
    @Operation(summary = "Get a specific block",
            description = "Fetches and returns detailed block data including gas stats, miner, and header fields.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Block data retrieved"),
            @ApiResponse(responseCode = "404", description = "Chain not configured or block not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "RPC call failed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/blocks/{chain}/{blockNumber}")
    public ResponseEntity<IndexedBlock> getBlock(
            @Parameter(description = "Chain key (e.g. ethereum)")
            @PathVariable String chain,
            @Parameter(description = "Block number")
            @PathVariable long blockNumber) {

        validateChainExists(chain);

        IndexedBlock block = rpcClientService.getIndexedBlock(chain, blockNumber);
        return ResponseEntity.ok(block);
    }

    // =========================================================================
    // GET /transactions/{txHash}
    // =========================================================================

    /**
     * Searches for a transaction by hash across all configured chains.
     *
     * <p>Iterates through each configured chain and attempts to fetch the
     * transaction receipt. Returns the first match found. In demo mode,
     * transaction-by-hash lookup is not supported.
     *
     * <p><b>Note:</b> For production use, this endpoint should be backed by
     * an indexed database or Parquet query engine for efficient hash-based
     * lookups. The current implementation makes sequential RPC calls.
     *
     * @param txHash the transaction hash (with 0x prefix)
     * @return transaction data and the chain it was found on
     */
    @Operation(summary = "Search for a transaction by hash",
            description = """
                    Searches across all configured chains for the given transaction hash.
                    Sequential RPC calls; for production use, back with an indexed data store.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction found"),
            @ApiResponse(responseCode = "404", description = "Transaction not found on any chain",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "501", description = "Not supported in demo mode",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/transactions/{txHash}")
    public ResponseEntity<Map<String, Object>> getTransaction(
            @Parameter(description = "Transaction hash (0x-prefixed)")
            @PathVariable String txHash) {

        if (rpcClientService.isDemoMode()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Not Implemented");
            body.put("message", "Transaction-by-hash lookup is not available in demo mode. "
                    + "Use block-level APIs instead.");
            return ResponseEntity.status(501).body(body);
        }

        // Try each configured chain
        for (String chain : properties.getChains().keySet()) {
            try {
                var receipt = rpcClientService.getTransactionReceipt(chain, txHash);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("chain", chain);
                response.put("transactionHash", receipt.getTransactionHash());
                response.put("blockNumber", receipt.getBlockNumber());
                response.put("blockHash", receipt.getBlockHash());
                response.put("from", receipt.getFrom());
                response.put("to", receipt.getTo());
                response.put("contractAddress", receipt.getContractAddress());
                response.put("status", receipt.getStatus());
                response.put("gasUsed", receipt.getGasUsed());
                response.put("effectiveGasPrice", receipt.getEffectiveGasPrice());
                response.put("transactionIndex", receipt.getTransactionIndex());
                response.put("logsCount", receipt.getLogs() != null ? receipt.getLogs().size() : 0);
                return ResponseEntity.ok(response);

            } catch (RpcClientService.RpcException e) {
                log.debug("Transaction {} not found on chain={}: {}", txHash, chain, e.getMessage());
            }
        }

        // Not found on any chain
        Map<String, Object> notFound = new LinkedHashMap<>();
        notFound.put("error", "Not Found");
        notFound.put("message", "Transaction %s not found on any configured chain".formatted(txHash));
        notFound.put("chainsSearched", properties.getChains().keySet());
        return ResponseEntity.status(404).body(notFound);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void validateChainExists(String chain) {
        if (!properties.getChains().containsKey(chain)) {
            throw new ChainNotFoundException(chain);
        }
    }
}
