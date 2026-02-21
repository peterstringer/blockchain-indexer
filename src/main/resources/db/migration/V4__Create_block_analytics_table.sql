-- Block-level analytics data for historical dashboard queries.
-- One row per indexed block; designed for efficient GROUP BY on
-- (chain, block_date), (chain, block_hour), and (chain) aggregations.
CREATE TABLE block_analytics (
    id                    BIGSERIAL PRIMARY KEY,
    chain                 VARCHAR(50)              NOT NULL,
    block_number          BIGINT                   NOT NULL,
    block_timestamp       TIMESTAMP WITH TIME ZONE NOT NULL,
    block_date            DATE                     NOT NULL,
    block_hour            SMALLINT                 NOT NULL,

    -- Gas pricing (stored in Gwei for direct chart display)
    base_fee_gwei         DOUBLE PRECISION,
    avg_gas_price_gwei    DOUBLE PRECISION,
    min_gas_price_gwei    DOUBLE PRECISION,
    max_gas_price_gwei    DOUBLE PRECISION,

    -- Block fullness
    gas_used              BIGINT                   NOT NULL,
    gas_limit             BIGINT                   NOT NULL,
    gas_used_percentage   DOUBLE PRECISION         NOT NULL,

    -- Transaction stats
    transaction_count     INTEGER                  NOT NULL,

    -- Transaction type breakdown
    tx_count_legacy       INTEGER                  NOT NULL DEFAULT 0,
    tx_count_eip1559      INTEGER                  NOT NULL DEFAULT 0,
    tx_count_contract     INTEGER                  NOT NULL DEFAULT 0,
    tx_count_failed       INTEGER                  NOT NULL DEFAULT 0,

    -- Average gas used per transaction type
    avg_gas_legacy        DOUBLE PRECISION,
    avg_gas_eip1559       DOUBLE PRECISION,
    avg_gas_contract      DOUBLE PRECISION,

    created_at            TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    CONSTRAINT uq_block_analytics_chain_block UNIQUE (chain, block_number)
);

-- Index for daily gas price analysis: GROUP BY chain, block_date
CREATE INDEX idx_ba_chain_date ON block_analytics (chain, block_date);

-- Index for hourly gas patterns: GROUP BY chain, block_hour
CREATE INDEX idx_ba_chain_hour ON block_analytics (chain, block_hour);

-- Index for time-range queries
CREATE INDEX idx_ba_chain_timestamp ON block_analytics (chain, block_timestamp);
