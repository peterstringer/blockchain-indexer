package com.peterstringer.blockchain.indexer.repository;

import com.peterstringer.blockchain.indexer.model.BlockAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for {@link BlockAnalytics} entities.
 *
 * <p>Provides aggregation queries for historical dashboard analytics including
 * daily gas prices, hourly patterns, block fullness, cross-chain comparison,
 * and transaction type distribution. All queries use native SQL for efficient
 * GROUP BY aggregations that do not map naturally to JPQL entity returns.
 */
@Repository
public interface BlockAnalyticsRepository extends JpaRepository<BlockAnalytics, Long> {

    /**
     * Daily gas price analysis: avg/min/max base fee and gas price grouped by date.
     *
     * @return rows of [chain, block_date, avg_base_fee, min_base_fee, max_base_fee, avg_gas_price]
     */
    @Query(nativeQuery = true, value = """
            SELECT chain,
                   block_date,
                   AVG(base_fee_gwei)       AS avg_base_fee,
                   MIN(base_fee_gwei)       AS min_base_fee,
                   MAX(base_fee_gwei)       AS max_base_fee,
                   AVG(avg_gas_price_gwei)  AS avg_gas_price
              FROM block_analytics
             WHERE (:chain IS NULL OR chain = :chain)
               AND block_date BETWEEN :fromDate AND :toDate
             GROUP BY chain, block_date
             ORDER BY block_date
            """)
    List<Object[]> findDailyGasPrices(@Param("chain") String chain,
                                      @Param("fromDate") LocalDate fromDate,
                                      @Param("toDate") LocalDate toDate);

    /**
     * Hourly gas patterns: average gas price by hour of day (0-23).
     *
     * @return rows of [chain, block_hour, avg_base_fee, avg_gas_price]
     */
    @Query(nativeQuery = true, value = """
            SELECT chain,
                   block_hour,
                   AVG(base_fee_gwei)       AS avg_base_fee,
                   AVG(avg_gas_price_gwei)  AS avg_gas_price
              FROM block_analytics
             WHERE (:chain IS NULL OR chain = :chain)
               AND block_date BETWEEN :fromDate AND :toDate
             GROUP BY chain, block_hour
             ORDER BY block_hour
            """)
    List<Object[]> findHourlyGasPatterns(@Param("chain") String chain,
                                         @Param("fromDate") LocalDate fromDate,
                                         @Param("toDate") LocalDate toDate);

    /**
     * Block fullness analysis: average gas utilization percentage per chain.
     *
     * @return rows of [chain, avg_fullness, min_fullness, max_fullness, block_count]
     */
    @Query(nativeQuery = true, value = """
            SELECT chain,
                   AVG(gas_used_percentage)  AS avg_fullness,
                   MIN(gas_used_percentage)  AS min_fullness,
                   MAX(gas_used_percentage)  AS max_fullness,
                   COUNT(*)                  AS block_count
              FROM block_analytics
             WHERE block_date BETWEEN :fromDate AND :toDate
             GROUP BY chain
             ORDER BY chain
            """)
    List<Object[]> findBlockFullness(@Param("fromDate") LocalDate fromDate,
                                     @Param("toDate") LocalDate toDate);

    /**
     * Cross-chain comparison: avg tx count, gas price, base fee, totals per chain.
     *
     * @return rows of [chain, avg_tx_count, avg_gas_price, avg_base_fee, total_txs, block_count]
     */
    @Query(nativeQuery = true, value = """
            SELECT chain,
                   AVG(transaction_count)    AS avg_tx_count,
                   AVG(avg_gas_price_gwei)   AS avg_gas_price,
                   AVG(base_fee_gwei)        AS avg_base_fee,
                   SUM(transaction_count)    AS total_txs,
                   COUNT(*)                  AS block_count
              FROM block_analytics
             WHERE block_date BETWEEN :fromDate AND :toDate
             GROUP BY chain
             ORDER BY chain
            """)
    List<Object[]> findCrossChainComparison(@Param("fromDate") LocalDate fromDate,
                                            @Param("toDate") LocalDate toDate);

    /**
     * Cross-chain comparison with normalised metrics: throughput, utilisation, failure rate.
     *
     * @return rows of [chain, avg_tx_count, avg_gas_price, avg_base_fee, total_txs, block_count,
     *                   avg_tx_per_second, avg_block_utilisation_pct, failure_rate_pct]
     */
    @Query(nativeQuery = true, value = """
            SELECT chain,
                   AVG(transaction_count)                                             AS avg_tx_count,
                   AVG(avg_gas_price_gwei)                                            AS avg_gas_price,
                   AVG(base_fee_gwei)                                                 AS avg_base_fee,
                   SUM(transaction_count)                                             AS total_txs,
                   COUNT(*)                                                           AS block_count,
                   AVG(transaction_count / NULLIF(actual_block_time_ms / 1000.0, 0))  AS avg_tx_per_second,
                   AVG(gas_used_percentage)                                           AS avg_block_utilisation_pct,
                   SUM(tx_count_failed) * 100.0 / NULLIF(SUM(transaction_count), 0)  AS failure_rate_pct
              FROM block_analytics
             WHERE block_date BETWEEN :fromDate AND :toDate
             GROUP BY chain
             ORDER BY chain
            """)
    List<Object[]> findCrossChainNormalised(@Param("fromDate") LocalDate fromDate,
                                            @Param("toDate") LocalDate toDate);

    /**
     * Transaction type analysis: counts and average gas by type per chain.
     *
     * @return rows of [chain, total_legacy, total_eip1559, total_contract, total_failed,
     *                   avg_gas_legacy, avg_gas_eip1559, avg_gas_contract]
     */
    @Query(nativeQuery = true, value = """
            SELECT chain,
                   SUM(tx_count_legacy)     AS total_legacy,
                   SUM(tx_count_eip1559)    AS total_eip1559,
                   SUM(tx_count_contract)   AS total_contract,
                   SUM(tx_count_failed)     AS total_failed,
                   AVG(avg_gas_legacy)      AS avg_gas_legacy,
                   AVG(avg_gas_eip1559)     AS avg_gas_eip1559,
                   AVG(avg_gas_contract)    AS avg_gas_contract
              FROM block_analytics
             WHERE (:chain IS NULL OR chain = :chain)
               AND block_date BETWEEN :fromDate AND :toDate
             GROUP BY chain
             ORDER BY chain
            """)
    List<Object[]> findTransactionTypeAnalysis(@Param("chain") String chain,
                                               @Param("fromDate") LocalDate fromDate,
                                               @Param("toDate") LocalDate toDate);

    /**
     * Gas market daily: avg base fee, effective gas price, priority fee, and min/max base fee.
     *
     * @return rows of [chain, block_date, avg_base_fee, avg_effective_gas_price, avg_priority_fee,
     *                   min_base_fee, max_base_fee]
     */
    @Query(nativeQuery = true, value = """
            SELECT chain,
                   block_date,
                   AVG(base_fee_gwei)            AS avg_base_fee,
                   AVG(avg_gas_price_gwei)        AS avg_effective_gas_price,
                   AVG(avg_priority_fee_gwei)     AS avg_priority_fee,
                   MIN(base_fee_gwei)             AS min_base_fee,
                   MAX(base_fee_gwei)             AS max_base_fee
              FROM block_analytics
             WHERE (:chain IS NULL OR chain = :chain)
               AND block_date BETWEEN :fromDate AND :toDate
             GROUP BY chain, block_date
             ORDER BY chain, block_date
            """)
    List<Object[]> findGasMarketDaily(@Param("chain") String chain,
                                      @Param("fromDate") LocalDate fromDate,
                                      @Param("toDate") LocalDate toDate);

    /**
     * Daily failure rate: total txs, failed txs, failure %, and avg gas price.
     *
     * @return rows of [chain, block_date, total_txs, failed_txs, failure_rate_pct, avg_gas_price]
     */
    @Query(nativeQuery = true, value = """
            SELECT chain,
                   block_date,
                   SUM(transaction_count)                                    AS total_txs,
                   SUM(tx_count_failed)                                      AS failed_txs,
                   CASE WHEN SUM(transaction_count) > 0
                        THEN SUM(tx_count_failed) * 100.0 / SUM(transaction_count)
                        ELSE 0 END                                           AS failure_rate_pct,
                   AVG(avg_gas_price_gwei)                                   AS avg_gas_price
              FROM block_analytics
             WHERE (:chain IS NULL OR chain = :chain)
               AND block_date BETWEEN :fromDate AND :toDate
             GROUP BY chain, block_date
             ORDER BY chain, block_date
            """)
    List<Object[]> findDailyFailureRate(@Param("chain") String chain,
                                        @Param("fromDate") LocalDate fromDate,
                                        @Param("toDate") LocalDate toDate);

    /**
     * Transaction density heatmap: avg tx count by day-of-week × hour-of-day.
     *
     * @return rows of [chain, block_day_of_week, block_hour, avg_tx_count, total_blocks]
     */
    @Query(nativeQuery = true, value = """
            SELECT chain,
                   block_day_of_week,
                   block_hour,
                   AVG(transaction_count) AS avg_tx_count,
                   COUNT(*)              AS total_blocks
              FROM block_analytics
             WHERE (:chain IS NULL OR chain = :chain)
               AND block_date BETWEEN :fromDate AND :toDate
               AND block_day_of_week IS NOT NULL
             GROUP BY chain, block_day_of_week, block_hour
             ORDER BY chain, block_day_of_week, block_hour
            """)
    List<Object[]> findTxDensityHeatmap(@Param("chain") String chain,
                                        @Param("fromDate") LocalDate fromDate,
                                        @Param("toDate") LocalDate toDate);

    /**
     * Data availability: earliest/latest dates and block counts per chain.
     *
     * @return rows of [chain, earliest_date, latest_date, block_count]
     */
    @Query(nativeQuery = true, value = """
            SELECT chain,
                   MIN(block_date) AS earliest_date,
                   MAX(block_date) AS latest_date,
                   COUNT(*)        AS block_count
              FROM block_analytics
             GROUP BY chain
             ORDER BY chain
            """)
    List<Object[]> findDataAvailability();
}
