package com.peterstringer.blockchain.indexer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * JPA entity mapping the {@code block_analytics} table.
 *
 * <p>Stores one row per indexed block with pre-computed analytics fields
 * optimized for dashboard aggregation queries (daily gas prices, hourly
 * patterns, block fullness, cross-chain comparison, transaction type
 * breakdown). Gas prices are stored in Gwei for direct chart display.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "block_analytics",
       uniqueConstraints = @UniqueConstraint(columnNames = {"chain", "block_number"}))
public class BlockAnalytics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain", nullable = false, length = 50)
    private String chain;

    @Column(name = "block_number", nullable = false)
    private Long blockNumber;

    @Column(name = "block_timestamp", nullable = false)
    private OffsetDateTime blockTimestamp;

    @Column(name = "block_date", nullable = false)
    private LocalDate blockDate;

    @Column(name = "block_hour", nullable = false)
    private Short blockHour;

    // ---- Gas pricing (Gwei) ----

    @Column(name = "base_fee_gwei")
    private Double baseFeeGwei;

    @Column(name = "avg_gas_price_gwei")
    private Double avgGasPriceGwei;

    @Column(name = "min_gas_price_gwei")
    private Double minGasPriceGwei;

    @Column(name = "max_gas_price_gwei")
    private Double maxGasPriceGwei;

    // ---- Block fullness ----

    @Column(name = "gas_used", nullable = false)
    private Long gasUsed;

    @Column(name = "gas_limit", nullable = false)
    private Long gasLimit;

    @Column(name = "gas_used_percentage", nullable = false)
    private Double gasUsedPercentage;

    // ---- Transaction stats ----

    @Column(name = "transaction_count", nullable = false)
    private Integer transactionCount;

    // ---- Transaction type breakdown ----

    @Column(name = "tx_count_legacy", nullable = false)
    private Integer txCountLegacy;

    @Column(name = "tx_count_eip1559", nullable = false)
    private Integer txCountEip1559;

    @Column(name = "tx_count_contract", nullable = false)
    private Integer txCountContract;

    @Column(name = "tx_count_failed", nullable = false)
    private Integer txCountFailed;

    // ---- Average gas per tx type ----

    @Column(name = "avg_gas_legacy")
    private Double avgGasLegacy;

    @Column(name = "avg_gas_eip1559")
    private Double avgGasEip1559;

    @Column(name = "avg_gas_contract")
    private Double avgGasContract;

    // ---- V5 columns ----

    @Column(name = "avg_priority_fee_gwei")
    private Double avgPriorityFeeGwei;

    @Column(name = "actual_block_time_ms")
    private Integer actualBlockTimeMs;

    @Column(name = "block_day_of_week")
    private Short blockDayOfWeek;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
