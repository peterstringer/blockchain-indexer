-- Time-series metrics for indexer performance monitoring
CREATE TABLE indexer_metrics (
    id            BIGSERIAL PRIMARY KEY,
    chain         VARCHAR(50)              NOT NULL,
    metric_name   VARCHAR(100)             NOT NULL,
    metric_value  DOUBLE PRECISION         NOT NULL,
    recorded_at   TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_metrics_chain_name_time ON indexer_metrics (chain, metric_name, recorded_at);
