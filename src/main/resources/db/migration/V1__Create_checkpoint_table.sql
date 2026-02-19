-- Tracks the last indexed block per chain for crash recovery and resumption
CREATE TABLE indexer_checkpoints (
    id                        BIGSERIAL PRIMARY KEY,
    chain                     VARCHAR(50)              NOT NULL UNIQUE,
    last_indexed_block        BIGINT                   NOT NULL,
    total_blocks_indexed      BIGINT                   DEFAULT 0,
    total_transactions_indexed BIGINT                  DEFAULT 0,
    last_updated              TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at                TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_checkpoints_chain ON indexer_checkpoints (chain);
