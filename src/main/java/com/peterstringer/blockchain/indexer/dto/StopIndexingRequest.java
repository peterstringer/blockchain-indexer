package com.peterstringer.blockchain.indexer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for the {@code POST /api/indexer/stop} endpoint.
 *
 * <p>If {@code chain} is null or blank, all running chains are stopped.
 *
 * <p>Example JSON (stop one chain):
 * <pre>
 * { "chain": "ethereum" }
 * </pre>
 *
 * <p>Example JSON (stop all):
 * <pre>
 * {}
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StopIndexingRequest {

    /** Chain key to stop, or {@code null}/blank to stop all chains. */
    private String chain;
}
