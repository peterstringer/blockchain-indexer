-- Track reverse-backfill progress: the lowest block number indexed so far
-- when indexing backward from the chain head toward the configured start block.
-- NULL means no reverse backfill has been performed yet.
ALTER TABLE indexer_checkpoints ADD COLUMN backfill_floor_block BIGINT;
