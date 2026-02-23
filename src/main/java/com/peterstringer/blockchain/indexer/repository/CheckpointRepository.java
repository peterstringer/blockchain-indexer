package com.peterstringer.blockchain.indexer.repository;

import com.peterstringer.blockchain.indexer.model.IndexerCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link IndexerCheckpoint} entities.
 *
 * <p>Provides crash-recovery semantics for the indexer. After each batch of
 * blocks is processed and written to Parquet, the checkpoint is updated so
 * that a restart resumes from the correct block number without gaps or
 * duplicates.
 *
 * <p>The {@link #updateCheckpoint} method performs an atomic, single-statement
 * update to minimize lock contention under concurrent indexing.
 */
@Repository
public interface CheckpointRepository extends JpaRepository<IndexerCheckpoint, Long> {

    /**
     * Finds the checkpoint for a specific chain.
     *
     * @param chain the chain identifier (e.g. "ethereum")
     * @return the checkpoint if one exists for this chain
     */
    Optional<IndexerCheckpoint> findByChain(String chain);

    /**
     * Returns all checkpoints ordered by the highest indexed block first.
     * Useful for status dashboards showing per-chain progress.
     *
     * @return all checkpoints, most-progressed chain first
     */
    @Query("SELECT c FROM IndexerCheckpoint c ORDER BY c.lastIndexedBlock DESC")
    List<IndexerCheckpoint> findAllOrderByLastIndexedBlockDesc();

    /**
     * Atomically updates the checkpoint for a chain after a batch completes.
     *
     * <p>Increments {@code totalBlocksIndexed} and {@code totalTransactionsIndexed}
     * by the given deltas rather than setting absolute values, so concurrent
     * writers on different block ranges do not overwrite each other's totals.
     *
     * @param chain              the chain identifier
     * @param lastIndexedBlock   the new high-water-mark block number
     * @param blocksIndexed      number of blocks in this batch
     * @param transactionsIndexed number of transactions in this batch
     * @param now                current timestamp
     * @return the number of rows updated (1 if the chain exists, 0 otherwise)
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE IndexerCheckpoint c
               SET c.lastIndexedBlock = :lastIndexedBlock,
                   c.totalBlocksIndexed = c.totalBlocksIndexed + :blocksIndexed,
                   c.totalTransactionsIndexed = c.totalTransactionsIndexed + :transactionsIndexed,
                   c.lastUpdated = :now
             WHERE c.chain = :chain
            """)
    int updateCheckpoint(@Param("chain") String chain,
                         @Param("lastIndexedBlock") Long lastIndexedBlock,
                         @Param("blocksIndexed") Long blocksIndexed,
                         @Param("transactionsIndexed") Long transactionsIndexed,
                         @Param("now") OffsetDateTime now);

    /**
     * Updates the backfill floor block and increments counters for reverse backfill progress.
     * Only updates the floor if the new value is lower than the current one (or current is null).
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE IndexerCheckpoint c
               SET c.backfillFloorBlock = :floorBlock,
                   c.totalBlocksIndexed = c.totalBlocksIndexed + :blocksIndexed,
                   c.totalTransactionsIndexed = c.totalTransactionsIndexed + :transactionsIndexed,
                   c.lastUpdated = :now
             WHERE c.chain = :chain
            """)
    int updateBackfillFloor(@Param("chain") String chain,
                            @Param("floorBlock") Long floorBlock,
                            @Param("blocksIndexed") Long blocksIndexed,
                            @Param("transactionsIndexed") Long transactionsIndexed,
                            @Param("now") OffsetDateTime now);
}
