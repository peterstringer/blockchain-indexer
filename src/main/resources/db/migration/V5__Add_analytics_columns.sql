-- Add new analytics columns to block_analytics for priority fee tracking,
-- block timing analysis, and day-of-week heatmap support.

-- Average priority fee (tip) in Gwei: avg(effective_gas_price - base_fee) across block txs.
-- Only populated going forward (requires transaction-level data at index time).
ALTER TABLE block_analytics ADD COLUMN avg_priority_fee_gwei DOUBLE PRECISION;

-- Milliseconds elapsed since previous block on the same chain.
-- NULL for the first indexed block per chain.
ALTER TABLE block_analytics ADD COLUMN actual_block_time_ms INTEGER;

-- Day of week: 0 = Sunday through 6 = Saturday, extracted from block_timestamp.
ALTER TABLE block_analytics ADD COLUMN block_day_of_week SMALLINT;

-- Backfill block_day_of_week from existing block_timestamp data.
UPDATE block_analytics SET block_day_of_week = EXTRACT(DOW FROM block_timestamp)::SMALLINT;

-- Index for heatmap queries: GROUP BY chain, block_day_of_week, block_hour
CREATE INDEX idx_ba_chain_dow_hour ON block_analytics (chain, block_day_of_week, block_hour);
