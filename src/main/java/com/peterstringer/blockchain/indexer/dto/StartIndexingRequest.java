package com.peterstringer.blockchain.indexer.dto;

import com.peterstringer.blockchain.indexer.service.BlockIndexerService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for the {@code POST /api/indexer/start} endpoint.
 *
 * <p>Example JSON:
 * <pre>
 * {
 *   "chain": "ethereum",
 *   "mode": "BACKFILL"
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StartIndexingRequest {

    /** Chain key matching a configured chain (e.g. "ethereum", "polygon"). */
    @NotBlank(message = "Chain name is required")
    private String chain;

    /** Indexing mode: BACKFILL for historical blocks, INCREMENTAL for chain-head tailing. */
    @NotNull(message = "Index mode is required (BACKFILL or INCREMENTAL)")
    private BlockIndexerService.IndexMode mode;
}
