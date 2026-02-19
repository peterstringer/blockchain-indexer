package com.peterstringer.blockchain.indexer.model.ws;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket message sent on {@code /topic/indexer/{chain}/blocks} when a
 * block has been indexed.
 *
 * <p>Provides key block data for real-time activity feeds and gas price
 * tickers. Only the most recent blocks in each batch are broadcast to
 * avoid flooding subscribers during high-throughput backfill.
 *
 * <p>Example JSON payload:
 * <pre>
 * {
 *   "chain":            "ethereum",
 *   "blockNumber":      18500123,
 *   "blockHash":        "0xabc...def",
 *   "transactionCount": 184,
 *   "gasUsed":          15000000,
 *   "baseFeeGwei":      25.3,
 *   "timestamp":        1708300800000
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockIndexedMessage {

    /** Chain key (e.g. "ethereum"). */
    private String chain;

    /** The block number that was indexed. */
    private Long blockNumber;

    /** The block hash (0x-prefixed hex string). */
    private String blockHash;

    /** Number of transactions in the block. */
    private Integer transactionCount;

    /** Total gas consumed by the block (in gas units). */
    private Long gasUsed;

    /** Base fee per gas in Gwei (null for pre-EIP-1559 blocks). */
    private Double baseFeeGwei;

    /** Block timestamp as epoch millis. */
    private Long timestamp;
}
