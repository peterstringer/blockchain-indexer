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
 * JPA entity mapping the {@code indexer_metrics} table.
 *
 * <p>Stores time-series performance metrics for each chain. Examples include
 * blocks-per-second throughput, RPC latency, queue depth, and Parquet write
 * duration. The composite index on {@code (chain, metric_name, recorded_at)}
 * supports efficient range queries for dashboards and alerting.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "indexer_metrics")
public class IndexerMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain", nullable = false, length = 50)
    private String chain;

    @Column(name = "metric_name", nullable = false, length = 100)
    private String metricName;

    @Column(name = "metric_value", nullable = false)
    private Double metricValue;

    @Column(name = "recorded_at")
    private OffsetDateTime recordedAt;
}
