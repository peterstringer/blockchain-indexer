package com.peterstringer.blockchain.indexer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO representing the current state of the indexer, served via the REST API.
 *
 * <p>Provides an at-a-glance view of whether the indexer is running,
 * what mode it is in, and per-chain progress with RPC health status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexerStatus {

    /**
     * Operating mode of the indexer.
     *
     * <ul>
     *   <li>{@code BACKFILL} — processing historical blocks to catch up.</li>
     *   <li>{@code INCREMENTAL} — tailing the chain head in real time.</li>
     *   <li>{@code STOPPED} — indexer is not running.</li>
     * </ul>
     */
    public enum Mode {
        BACKFILL,
        INCREMENTAL,
        STOPPED
    }

    private boolean running;
    private Mode mode;
    private Map<String, ChainStatus> chains;

    /**
     * Per-chain status snapshot including progress counters and RPC health.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChainStatus {
        private Long lastBlock;
        private Long blocksIndexed;
        private Long transactionsIndexed;
        private Double blocksPerSecond;
        private String rpcHealth;
    }
}
