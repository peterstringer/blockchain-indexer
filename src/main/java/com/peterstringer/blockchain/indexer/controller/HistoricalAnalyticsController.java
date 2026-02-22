package com.peterstringer.blockchain.indexer.controller;

import com.peterstringer.blockchain.indexer.repository.BlockAnalyticsRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for historical analytics queries backed by the
 * {@code block_analytics} PostgreSQL table.
 *
 * <p>All endpoints accept date-range parameters and return pre-aggregated
 * data optimized for dashboard chart rendering. Gas prices are already
 * stored in Gwei so the frontend does not need to convert.
 */
@RestController
@RequestMapping("/api/analytics/historical")
@Tag(name = "Historical Analytics",
     description = "Aggregated analytics from persisted block data")
public class HistoricalAnalyticsController {

    private final BlockAnalyticsRepository repository;

    public HistoricalAnalyticsController(BlockAnalyticsRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "Daily gas price analysis",
               description = "Returns daily avg/min/max base fee and gas price over time")
    @GetMapping("/gas-prices/daily")
    public ResponseEntity<List<Map<String, Object>>> getDailyGasPrices(
            @Parameter(description = "Chain filter (null = all chains)")
            @RequestParam(required = false) String chain,
            @Parameter(description = "Start date (inclusive, ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (inclusive, ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<Object[]> rows = repository.findDailyGasPrices(chain, from, to);
        List<Map<String, Object>> result = rows.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chain", row[0]);
            map.put("date", row[1].toString());
            map.put("avgBaseFee", toDouble(row[2]));
            map.put("minBaseFee", toDouble(row[3]));
            map.put("maxBaseFee", toDouble(row[4]));
            map.put("avgGasPrice", toDouble(row[5]));
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Hourly gas patterns",
               description = "Average gas price by hour of day (0-23)")
    @GetMapping("/gas-prices/hourly")
    public ResponseEntity<List<Map<String, Object>>> getHourlyGasPatterns(
            @RequestParam(required = false) String chain,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<Object[]> rows = repository.findHourlyGasPatterns(chain, from, to);
        List<Map<String, Object>> result = rows.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chain", row[0]);
            map.put("hour", ((Number) row[1]).intValue());
            map.put("avgBaseFee", toDouble(row[2]));
            map.put("avgGasPrice", toDouble(row[3]));
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Block fullness by chain",
               description = "Average gas utilization percentage per chain")
    @GetMapping("/block-fullness")
    public ResponseEntity<List<Map<String, Object>>> getBlockFullness(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<Object[]> rows = repository.findBlockFullness(from, to);
        List<Map<String, Object>> result = rows.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chain", row[0]);
            map.put("avgFullness", toDouble(row[1]));
            map.put("minFullness", toDouble(row[2]));
            map.put("maxFullness", toDouble(row[3]));
            map.put("blockCount", ((Number) row[4]).longValue());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Cross-chain comparison",
               description = "Average tx count and gas price by chain")
    @GetMapping("/cross-chain")
    public ResponseEntity<List<Map<String, Object>>> getCrossChainComparison(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<Object[]> rows = repository.findCrossChainComparison(from, to);
        List<Map<String, Object>> result = rows.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chain", row[0]);
            map.put("avgTxCount", toDouble(row[1]));
            map.put("avgGasPrice", toDouble(row[2]));
            map.put("avgBaseFee", toDouble(row[3]));
            map.put("totalTxs", ((Number) row[4]).longValue());
            map.put("blockCount", ((Number) row[5]).longValue());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Cross-chain comparison with normalised metrics",
               description = "Includes throughput (tx/sec), block utilisation, and failure rate alongside base metrics")
    @GetMapping("/cross-chain-normalised")
    public ResponseEntity<List<Map<String, Object>>> getCrossChainNormalised(
            @Parameter(description = "Start date (inclusive, ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (inclusive, ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<Object[]> rows = repository.findCrossChainNormalised(from, to);
        List<Map<String, Object>> result = rows.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chain", row[0]);
            map.put("avgTxCount", toDouble(row[1]));
            map.put("avgGasPrice", toDouble(row[2]));
            map.put("avgBaseFee", toDouble(row[3]));
            map.put("totalTxs", ((Number) row[4]).longValue());
            map.put("blockCount", ((Number) row[5]).longValue());
            map.put("avgTxPerSecond", toDouble(row[6]));
            map.put("avgBlockUtilisationPercent", toDouble(row[7]));
            map.put("failureRatePercent", toDouble(row[8]));
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Transaction type analysis",
               description = "Count and avg gas by tx type (legacy, EIP-1559, contract creation)")
    @GetMapping("/transaction-types")
    public ResponseEntity<List<Map<String, Object>>> getTransactionTypeAnalysis(
            @RequestParam(required = false) String chain,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<Object[]> rows = repository.findTransactionTypeAnalysis(chain, from, to);
        List<Map<String, Object>> result = rows.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chain", row[0]);
            map.put("totalLegacy", ((Number) row[1]).longValue());
            map.put("totalEip1559", ((Number) row[2]).longValue());
            map.put("totalContract", ((Number) row[3]).longValue());
            map.put("totalFailed", ((Number) row[4]).longValue());
            map.put("avgGasLegacy", toDouble(row[5]));
            map.put("avgGasEip1559", toDouble(row[6]));
            map.put("avgGasContract", toDouble(row[7]));
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Gas market daily",
               description = "Daily base fee, effective gas price, and priority fee trends")
    @GetMapping("/gas-market")
    public ResponseEntity<List<Map<String, Object>>> getGasMarket(
            @Parameter(description = "Chain filter (null = all chains)")
            @RequestParam(required = false) String chain,
            @Parameter(description = "Start date (inclusive, ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (inclusive, ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<Object[]> rows = repository.findGasMarketDaily(chain, from, to);
        List<Map<String, Object>> result = rows.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chain", row[0]);
            map.put("date", row[1].toString());
            map.put("avgBaseFeeGwei", toDouble(row[2]));
            map.put("avgEffectiveGasPriceGwei", toDouble(row[3]));
            map.put("avgPriorityFeeGwei", toDouble(row[4]));
            map.put("minBaseFeeGwei", toDouble(row[5]));
            map.put("maxBaseFeeGwei", toDouble(row[6]));
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Daily failure rate",
               description = "Daily failed transaction rate with gas price for correlation analysis")
    @GetMapping("/failure-rate")
    public ResponseEntity<List<Map<String, Object>>> getFailureRate(
            @Parameter(description = "Chain filter (null = all chains)")
            @RequestParam(required = false) String chain,
            @Parameter(description = "Start date (inclusive, ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (inclusive, ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<Object[]> rows = repository.findDailyFailureRate(chain, from, to);
        List<Map<String, Object>> result = rows.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chain", row[0]);
            map.put("date", row[1].toString());
            map.put("totalTransactions", ((Number) row[2]).longValue());
            map.put("failedTransactions", ((Number) row[3]).longValue());
            map.put("failureRatePercent", toDouble(row[4]));
            map.put("avgGasPriceGwei", toDouble(row[5]));
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Transaction density heatmap",
               description = "Average transaction count by day-of-week (0=Sun..6=Sat) and hour (0-23)")
    @GetMapping("/tx-density-heatmap")
    public ResponseEntity<List<Map<String, Object>>> getTxDensityHeatmap(
            @Parameter(description = "Chain filter (null = all chains)")
            @RequestParam(required = false) String chain,
            @Parameter(description = "Start date (inclusive, ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (inclusive, ISO format)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<Object[]> rows = repository.findTxDensityHeatmap(chain, from, to);
        List<Map<String, Object>> result = rows.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chain", row[0]);
            map.put("dayOfWeek", ((Number) row[1]).intValue());
            map.put("hour", ((Number) row[2]).intValue());
            map.put("avgTransactionCount", toDouble(row[3]));
            map.put("totalBlocks", ((Number) row[4]).longValue());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Data availability per chain",
               description = "Shows earliest/latest dates and block counts for each chain")
    @GetMapping("/data-availability")
    public ResponseEntity<List<Map<String, Object>>> getDataAvailability() {
        List<Object[]> rows = repository.findDataAvailability();
        List<Map<String, Object>> result = rows.stream().map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chain", row[0]);
            map.put("earliestDate", row[1].toString());
            map.put("latestDate", row[2].toString());
            map.put("blockCount", ((Number) row[3]).longValue());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    private static Double toDouble(Object val) {
        return val != null ? ((Number) val).doubleValue() : null;
    }
}
