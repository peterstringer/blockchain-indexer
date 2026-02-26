package com.peterstringer.blockchain.indexer.model.ws;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket message sent periodically on {@code /topic/indexer/{chain}/progress}.
 *
 * <p>Provides a real-time snapshot of indexing progress for a single chain,
 * suitable for dashboard progress bars and throughput charts.
 *
 * <p>Example JSON payload:
 * <pre>
 * {
 *   "chain":                  "ethereum",
 *   "currentBlock":           18500000,
 *   "latestBlock":            19000000,
 *   "blocksProcessed":        500000,
 *   "transactionsProcessed":  12500000,
 *   "blocksPerSecond":        42.5,
 *   "estimatedTimeRemaining": "2h 15m",
 *   "timestamp":              1708300800000
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexerProgressMessage {

    /** Chain key (e.g. "ethereum", "polygon"). */
    private String chain;

    /** The most recently indexed block number. */
    private Long currentBlock;

    /** The latest known block on the chain (target for backfill). */
    private Long latestBlock;

    /** Cumulative blocks processed since indexing started. */
    private Long blocksProcessed;

    /** Cumulative transactions processed since indexing started. */
    private Long transactionsProcessed;

    /** Current throughput in blocks per second. */
    private Double blocksPerSecond;

    /** Human-readable ETA (e.g. "2h 15m", "05:32", "N/A"). */
    private String estimatedTimeRemaining;

    /** Epoch millis when this message was generated. */
    private Long timestamp;

    /** Reverse backfill progress as a percentage (0.0–100.0), null when not backfilling. */
    private Double backfillProgress;

    /** Lowest block number reached during reverse backfill. */
    private Long backfillFloorBlock;

    /** Configured start block — the target floor for reverse backfill. */
    private Long backfillTargetBlock;

    /** Whether the reverse backfill has completed. */
    private Boolean reverseBackfillComplete;
}
