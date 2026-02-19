package com.peterstringer.blockchain.indexer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * JPA entity mapping the {@code indexer_checkpoints} table.
 *
 * <p>Tracks the last successfully indexed block for each chain, enabling
 * crash recovery and resumption without re-processing already-indexed blocks.
 * Updated atomically after each batch completes so that progress is never
 * lost even on unexpected shutdown.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "indexer_checkpoints")
public class IndexerCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain", nullable = false, unique = true, length = 50)
    private String chain;

    @Column(name = "last_indexed_block", nullable = false)
    private Long lastIndexedBlock;

    @Column(name = "total_blocks_indexed")
    private Long totalBlocksIndexed = 0L;

    @Column(name = "total_transactions_indexed")
    private Long totalTransactionsIndexed = 0L;

    @Column(name = "last_updated")
    private OffsetDateTime lastUpdated;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
