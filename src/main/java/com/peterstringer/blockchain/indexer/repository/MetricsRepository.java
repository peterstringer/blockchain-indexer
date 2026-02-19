package com.peterstringer.blockchain.indexer.repository;

import com.peterstringer.blockchain.indexer.model.IndexerMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Repository for {@link IndexerMetric} entities.
 *
 * <p>Provides access to the time-series metrics table used for monitoring
 * indexer performance. Metrics include blocks-per-second throughput, RPC
 * call latency, queue depth, and Parquet write duration.
 *
 * <p>The underlying table is indexed on {@code (chain, metric_name, recorded_at)}
 * to support efficient time-range queries for dashboards and alerting.
 */
@Repository
public interface MetricsRepository extends JpaRepository<IndexerMetric, Long> {

    /**
     * Finds metric data points for a specific chain and metric name recorded
     * after a given timestamp. Results are ordered chronologically.
     *
     * @param chain      the chain identifier (e.g. "ethereum")
     * @param metricName the metric name (e.g. "blocks_per_second")
     * @param after      the start of the time window (exclusive)
     * @return matching metrics in chronological order
     */
    List<IndexerMetric> findByChainAndMetricNameAndRecordedAtAfterOrderByRecordedAtAsc(
            String chain, String metricName, OffsetDateTime after);

    /**
     * Computes the average value of a metric over a time window for a chain.
     * Useful for dashboard summaries and SLA calculations.
     *
     * @param chain      the chain identifier
     * @param metricName the metric name
     * @param from       start of the window (inclusive)
     * @param to         end of the window (inclusive)
     * @return the average metric value, or {@code null} if no data points exist
     */
    @Query("""
            SELECT AVG(m.metricValue) FROM IndexerMetric m
             WHERE m.chain = :chain
               AND m.metricName = :metricName
               AND m.recordedAt BETWEEN :from AND :to
            """)
    Double averageMetricValue(@Param("chain") String chain,
                              @Param("metricName") String metricName,
                              @Param("from") OffsetDateTime from,
                              @Param("to") OffsetDateTime to);

    /**
     * Computes the maximum value of a metric over a time window for a chain.
     * Useful for detecting peak load or latency spikes.
     *
     * @param chain      the chain identifier
     * @param metricName the metric name
     * @param from       start of the window (inclusive)
     * @param to         end of the window (inclusive)
     * @return the maximum metric value, or {@code null} if no data points exist
     */
    @Query("""
            SELECT MAX(m.metricValue) FROM IndexerMetric m
             WHERE m.chain = :chain
               AND m.metricName = :metricName
               AND m.recordedAt BETWEEN :from AND :to
            """)
    Double maxMetricValue(@Param("chain") String chain,
                          @Param("metricName") String metricName,
                          @Param("from") OffsetDateTime from,
                          @Param("to") OffsetDateTime to);
}
