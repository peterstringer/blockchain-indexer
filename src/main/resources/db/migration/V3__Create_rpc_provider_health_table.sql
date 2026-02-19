-- Circuit breaker state for RPC provider health tracking
CREATE TABLE rpc_provider_health (
    id                BIGSERIAL PRIMARY KEY,
    chain             VARCHAR(50)              NOT NULL,
    provider_url_hash VARCHAR(64)              NOT NULL,
    state             VARCHAR(20)              NOT NULL,
    success_count     BIGINT                   DEFAULT 0,
    failure_count     BIGINT                   DEFAULT 0,
    last_failure_time TIMESTAMP WITH TIME ZONE,
    last_success_time TIMESTAMP WITH TIME ZONE,
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    CONSTRAINT uq_provider_chain_hash UNIQUE (chain, provider_url_hash)
);
