package com.peterstringer.blockchain.indexer.service;

import com.peterstringer.blockchain.indexer.model.BlockAnalytics;
import com.peterstringer.blockchain.indexer.model.IndexedBlock;
import com.peterstringer.blockchain.indexer.model.IndexedTransaction;
import com.peterstringer.blockchain.indexer.repository.BlockAnalyticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts indexed blocks into compact analytics summaries and persists
 * them to PostgreSQL for historical dashboard queries.
 *
 * <p>This service is called from {@link BlockIndexerService} after each
 * batch of blocks is indexed. Analytics persistence is non-blocking: if
 * it fails, the error is logged and indexing continues uninterrupted.
 */
@Service
public class BlockAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(BlockAnalyticsService.class);
    private static final double GWEI_DIVISOR = 1_000_000_000.0;

    private final BlockAnalyticsRepository repository;

    /**
     * Tracks the last seen block timestamp (epoch millis) per chain for
     * computing actual_block_time_ms. After a restart this map is empty,
     * so the first block per chain will have a null block time — which is
     * the expected behavior.
     */
    private final ConcurrentHashMap<String, Long> lastBlockTimestamp = new ConcurrentHashMap<>();

    public BlockAnalyticsService(BlockAnalyticsRepository repository) {
        this.repository = repository;
    }

    /**
     * Persists analytics data for a batch of indexed blocks.
     * Duplicates are silently ignored via the UNIQUE constraint on (chain, block_number).
     */
    public void persistBatch(List<IndexedBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return;

        List<BlockAnalytics> entities = blocks.stream()
                .map(this::toAnalytics)
                .toList();

        try {
            repository.saveAll(entities);
            log.debug("Persisted {} block analytics records", entities.size());
        } catch (Exception e) {
            log.warn("Batch analytics persist failed ({} records): {} — falling back to individual saves",
                    entities.size(), e.getMessage());
            int saved = 0;
            for (BlockAnalytics entity : entities) {
                try {
                    repository.save(entity);
                    saved++;
                } catch (Exception ignored) {
                    // Duplicate or constraint violation — safe to skip
                }
            }
            if (saved > 0) {
                log.debug("Individually saved {} of {} analytics records", saved, entities.size());
            }
        }
    }

    private BlockAnalytics toAnalytics(IndexedBlock block) {
        OffsetDateTime blockTime = Instant.ofEpochMilli(block.getTimestamp())
                .atOffset(ZoneOffset.UTC);
        LocalDate blockDate = blockTime.toLocalDate();
        short blockHour = (short) blockTime.getHour();

        List<IndexedTransaction> txs = block.getTransactions();
        int legacyCount = 0, eip1559Count = 0, contractCount = 0, failedCount = 0;
        long legacyGasSum = 0, eip1559GasSum = 0, contractGasSum = 0;

        // Priority fee computation: accumulate (effectiveGasPrice - baseFee) across txs
        Long baseFeeWei = block.getBaseFeePerGas();
        long priorityFeeSum = 0;
        int priorityFeeCount = 0;

        for (IndexedTransaction tx : txs) {
            boolean isContract = tx.getTo() == null;
            int type = tx.getType() != null ? tx.getType() : 0;

            if (isContract) {
                contractCount++;
                if (tx.getGasUsed() != null) contractGasSum += tx.getGasUsed();
            } else if (type == 2) {
                eip1559Count++;
                if (tx.getGasUsed() != null) eip1559GasSum += tx.getGasUsed();
            } else {
                legacyCount++;
                if (tx.getGasUsed() != null) legacyGasSum += tx.getGasUsed();
            }

            if (!tx.isSuccess()) failedCount++;

            // Compute priority fee for this transaction
            if (baseFeeWei != null) {
                Long effectivePrice = tx.getEffectiveGasPrice();
                if (effectivePrice == null) {
                    effectivePrice = tx.getGasPrice();
                }
                if (effectivePrice != null) {
                    long tipWei = effectivePrice - baseFeeWei;
                    if (tipWei < 0) tipWei = 0;
                    priorityFeeSum += tipWei;
                    priorityFeeCount++;
                }
            }
        }

        // avg_priority_fee_gwei: null if no base fee or no transactions with price data
        Double avgPriorityFeeGwei = (priorityFeeCount > 0)
                ? (priorityFeeSum / (double) priorityFeeCount) / GWEI_DIVISOR
                : null;

        // actual_block_time_ms: difference from previous block's timestamp on same chain
        String chain = block.getChain();
        Long currentTimestamp = block.getTimestamp();
        Long previousTimestamp = lastBlockTimestamp.put(chain, currentTimestamp);
        Integer actualBlockTimeMs = null;
        if (previousTimestamp != null && currentTimestamp != null) {
            long diffMs = currentTimestamp - previousTimestamp;
            if (diffMs >= 0) {
                actualBlockTimeMs = (int) diffMs;
            }
        }

        // block_day_of_week: Java DayOfWeek (Mon=1..Sun=7) → SQL DOW (Sun=0..Sat=6)
        DayOfWeek javaDow = blockTime.getDayOfWeek();
        short blockDayOfWeek = (short) (javaDow.getValue() % 7); // Mon=1,Tue=2,...,Sat=6,Sun=7%7=0

        return BlockAnalytics.builder()
                .chain(chain)
                .blockNumber(block.getBlockNumber())
                .blockTimestamp(blockTime)
                .blockDate(blockDate)
                .blockHour(blockHour)
                .baseFeeGwei(weiToGwei(baseFeeWei))
                .avgGasPriceGwei(weiToGwei(block.getAvgGasPrice()))
                .minGasPriceGwei(weiToGwei(block.getMinGasPrice()))
                .maxGasPriceGwei(weiToGwei(block.getMaxGasPrice()))
                .gasUsed(block.getGasUsed() != null ? block.getGasUsed() : 0L)
                .gasLimit(block.getGasLimit() != null ? block.getGasLimit() : 1L)
                .gasUsedPercentage(block.getGasUsedPercentage() != null
                        ? block.getGasUsedPercentage() : 0.0)
                .transactionCount(block.getTransactionCount() != null
                        ? block.getTransactionCount() : 0)
                .txCountLegacy(legacyCount)
                .txCountEip1559(eip1559Count)
                .txCountContract(contractCount)
                .txCountFailed(failedCount)
                .avgGasLegacy(legacyCount > 0 ? (double) legacyGasSum / legacyCount : null)
                .avgGasEip1559(eip1559Count > 0 ? (double) eip1559GasSum / eip1559Count : null)
                .avgGasContract(contractCount > 0 ? (double) contractGasSum / contractCount : null)
                .avgPriorityFeeGwei(avgPriorityFeeGwei)
                .actualBlockTimeMs(actualBlockTimeMs)
                .blockDayOfWeek(blockDayOfWeek)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private static Double weiToGwei(Long wei) {
        return wei != null ? wei / GWEI_DIVISOR : null;
    }
}
