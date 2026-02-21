# Multi-Chain Block Indexer — Project Summary

## Overview

A full-stack blockchain indexer that indexes Ethereum, Polygon, and Arbitrum blocks and transactions in real-time. Built with Spring Boot 4.0.2 (JDK 25) backend and React 19 + TypeScript frontend. Supports both live RPC indexing and a deterministic demo mode with synthetic data.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  React 19 Dashboard (Vite 7 + Tailwind v4 + Recharts 3.7)  │
│  TanStack Query v5 for server state, WebSocket for realtime │
├─────────────────────────────────────────────────────────────┤
│         REST API (Spring Boot 4.0.2, port 8080)             │
│  /api/indexer/*       — status, start, stop, checkpoints    │
│  /api/analytics/*     — live gas prices                     │
│  /api/analytics/historical/* — aggregated block analytics   │
├─────────────────────────────────────────────────────────────┤
│  WebSocket STOMP/SockJS (/ws)                               │
│  /topic/indexer/{chain}/progress — indexing throughput       │
│  /topic/indexer/{chain}/blocks   — individual block events  │
│  /topic/indexer/{chain}/rpc-health — provider circuit state │
├─────────────────────────────────────────────────────────────┤
│  PostgreSQL (Flyway V1–V4 migrations)                       │
│  Parquet files (Snappy, partitioned by chain+date)          │
└─────────────────────────────────────────────────────────────┘
```

## Chains Configured

| Chain    | Chain ID | Start Block   | Block Time |
|----------|----------|---------------|------------|
| Ethereum | 1        | 21,000,000    | ~12s       |
| Polygon  | 137      | 65,000,000    | ~2s        |
| Arbitrum | 42161    | 275,000,000   | ~0.25s     |

Each chain has 2 RPC providers (Alchemy + Infura) with circuit breaker health management.

## Demo Mode

When `DEMO_MODE=true`, `SyntheticDataProvider` generates deterministic blocks (seed 42) with:
- 100–300 transactions per block, 60–95% gas fill rate
- Gas: 20–100 Gwei with sinusoidal daily cycle peaking at 18:00 UTC, 30% weekend reduction, 5% congestion spikes
- Transaction mix: 70% EIP-1559, 20% Legacy, 10% Contract creation
- 98% success rate, value range 0.001–1000 ETH
- 1000 synthetic blocks per chain by default

---

## Data Available

### PostgreSQL Tables

#### 1. `block_analytics` (V4 migration) — Primary analytics data

One row per indexed block (~250 bytes). This is the main data source for all historical analytics.

| Column                | Type                      | Description                                    |
|-----------------------|---------------------------|------------------------------------------------|
| `chain`               | VARCHAR(50)               | "ethereum", "polygon", or "arbitrum"           |
| `block_number`        | BIGINT                    | Block height                                   |
| `block_timestamp`     | TIMESTAMP WITH TIME ZONE  | When the block was produced                    |
| `block_date`          | DATE                      | Pre-extracted for GROUP BY (daily aggregation) |
| `block_hour`          | SMALLINT (0–23)           | Pre-extracted for GROUP BY (hourly patterns)   |
| `base_fee_gwei`       | DOUBLE (nullable)         | EIP-1559 base fee in Gwei                      |
| `avg_gas_price_gwei`  | DOUBLE (nullable)         | Mean gas price across all txs, in Gwei         |
| `min_gas_price_gwei`  | DOUBLE (nullable)         | Lowest gas price in block, in Gwei             |
| `max_gas_price_gwei`  | DOUBLE (nullable)         | Highest gas price in block, in Gwei            |
| `gas_used`            | BIGINT                    | Total gas consumed                             |
| `gas_limit`           | BIGINT                    | Block gas limit                                |
| `gas_used_percentage` | DOUBLE                    | Block fullness: (used/limit)*100               |
| `transaction_count`   | INTEGER                   | Total transactions in block                    |
| `tx_count_legacy`     | INTEGER                   | Type 0 (legacy) transactions                   |
| `tx_count_eip1559`    | INTEGER                   | Type 2 (EIP-1559) transactions                 |
| `tx_count_contract`   | INTEGER                   | Contract creation txs (to == null)             |
| `tx_count_failed`     | INTEGER                   | Transactions with failed status                |
| `avg_gas_legacy`      | DOUBLE (nullable)         | Avg gas used by legacy txs                     |
| `avg_gas_eip1559`     | DOUBLE (nullable)         | Avg gas used by EIP-1559 txs                   |
| `avg_gas_contract`    | DOUBLE (nullable)         | Avg gas used by contract creation txs          |

**Indexes:** `(chain, block_date)`, `(chain, block_hour)`, `(chain, block_timestamp)`
**Unique constraint:** `(chain, block_number)` — idempotent inserts

**Data volume:** ~21,600 rows/day (3 chains × ~7,200 blocks/day), ~5 MB/day, ~1.8 GB/year.

#### 2. `indexer_checkpoints` (V1) — Crash recovery

| Column                       | Type     | Description                        |
|------------------------------|----------|------------------------------------|
| `chain`                      | VARCHAR  | Unique per chain                   |
| `last_indexed_block`         | BIGINT   | Resume point after restart         |
| `total_blocks_indexed`       | BIGINT   | Cumulative block count             |
| `total_transactions_indexed` | BIGINT   | Cumulative transaction count       |

#### 3. `indexer_metrics` (V2) — Performance time-series

| Column         | Type             | Description                           |
|----------------|------------------|---------------------------------------|
| `chain`        | VARCHAR          |                                       |
| `metric_name`  | VARCHAR(100)     | e.g. "blocks_per_second"              |
| `metric_value` | DOUBLE PRECISION |                                       |
| `recorded_at`  | TIMESTAMPTZ      | When metric was recorded              |

#### 4. `rpc_provider_health` (V3) — Circuit breaker state

| Column              | Type    | Description                                  |
|---------------------|---------|----------------------------------------------|
| `chain`             | VARCHAR |                                              |
| `provider_url_hash` | VARCHAR | SHA-256 of provider URL (not stored in plain) |
| `state`             | VARCHAR | CLOSED (healthy), OPEN (broken), HALF_OPEN   |
| `success_count`     | BIGINT  |                                              |
| `failure_count`     | BIGINT  |                                              |

### In-Memory Models (available during indexing)

#### IndexedBlock

Full block data built from Web3j RPC responses. Available in memory during indexing pipeline and written to Parquet.

Key fields: `chain`, `chainId`, `blockNumber`, `blockHash`, `parentHash`, `timestamp` (epoch ms), `miner`, `size`, `gasLimit`, `gasUsed`, `gasUsedPercentage`, `baseFeePerGas` (wei), `avgGasPrice` (wei), `medianGasPrice` (wei), `minGasPrice` (wei), `maxGasPrice` (wei), `transactionCount`, `totalValue` (wei string), `extraData`, `transactions` (list).

#### IndexedTransaction

Denormalized transaction + receipt data. Embedded in IndexedBlock during processing.

Key fields: `chain`, `hash`, `blockNumber`, `transactionIndex`, `from`, `to` (null = contract creation), `contractAddress`, `value` (wei string), `gas`, `gasPrice` (wei), `maxFeePerGas` (wei), `maxPriorityFeePerGas` (wei), `effectiveGasPrice` (wei), `gasUsed`, `type` (0=legacy, 1=access list, 2=EIP-1559), `success`, `input`, `inputLength`, `nonce`.

### Parquet Files

Written to `./output/` partitioned by chain and date. Two schemas:
- **Blocks:** All IndexedBlock fields except transactions list
- **Transactions:** All IndexedTransaction fields

Note: Parquet has JDK 25 compatibility issues (Subject.getSubject() removed). Data is still written but reading may fail.

---

## Existing REST API Endpoints

### Indexer Control
- `GET /api/indexer/status` → IndexerStatus (running state, per-chain progress)
- `GET /api/indexer/health` → HealthResponse (uptime, demo mode flag)
- `POST /api/indexer/start` → Start indexing (optional chain filter, mode: BACKFILL/INCREMENTAL)
- `POST /api/indexer/stop` → Stop indexing
- `GET /api/indexer/checkpoints` → List of IndexerCheckpoint per chain

### Live Analytics
- `GET /api/analytics/gas-prices?chain=` → GasPriceAggregation[] (from recent in-memory blocks)

### Historical Analytics (all accept `from` and `to` as ISO dates, e.g. `2024-12-01`)

| Endpoint | Optional Params | Returns |
|----------|-----------------|---------|
| `GET /api/analytics/historical/gas-prices/daily` | `chain` | Daily avg/min/max base fee and gas price per chain |
| `GET /api/analytics/historical/gas-prices/hourly` | `chain` | Hourly (0–23 UTC) avg base fee and gas price per chain |
| `GET /api/analytics/historical/block-fullness` | — | Avg/min/max gas utilization % per chain |
| `GET /api/analytics/historical/cross-chain` | — | Avg tx count, gas price, base fee, totals per chain |
| `GET /api/analytics/historical/transaction-types` | `chain` | Legacy/EIP-1559/contract/failed counts + avg gas per type |
| `GET /api/analytics/historical/data-availability` | — | Earliest/latest dates and block counts per chain |

---

## WebSocket Topics (STOMP over SockJS at `/ws`)

| Topic | Message Type | Key Fields |
|-------|-------------|------------|
| `/topic/indexer/{chain}/progress` | IndexerProgressMessage | currentBlock, latestBlock, blocksPerSecond, estimatedTimeRemaining |
| `/topic/indexer/{chain}/blocks` | BlockIndexedMessage | blockNumber, transactionCount, gasUsed, baseFeeGwei |
| `/topic/indexer/{chain}/rpc-health` | RpcHealthMessage | providersTotal, providersHealthy, providerStates[] |

---

## Frontend Dashboard Structure

### Dashboard Tabs
1. **Overview** — Status cards, chain progress bars, checkpoint table
2. **Analytics** — Two sub-tabs:
   - **Real-Time** — Charts from WebSocket data (~50 recent blocks in memory):
     - Multi-chain throughput (blocks/sec over time)
     - Gas prices by chain (line chart)
     - Transaction volume by chain (bar chart)
     - Per-chain gas utilization (individual charts)
   - **Historical** — Charts from PostgreSQL aggregation queries:
     - Date range picker with presets (7D, 30D, 90D, All) + optional chain filter
     - Daily gas price trends (ComposedChart: Area bands for min/max, Line for avg)
     - Hourly gas patterns (BarChart: grouped bars per hour 0–23 UTC)
     - Block fullness by chain (BarChart: avg gas utilization %, chain-colored)
     - Cross-chain comparison (BarChart: dual Y-axis for avg txs and gas price)
     - Transaction type breakdown (PieChart + horizontal BarChart for avg gas)

### Tech Stack
- React 19, TypeScript (strict mode, `noUncheckedIndexedAccess`)
- Vite 7, Tailwind CSS v4 (dark theme with CSS custom properties)
- Recharts 3.7 for all visualizations
- TanStack Query v5 (60s staleTime for historical, real-time for live data)
- Lucide React for icons

---

## Key Design Patterns

- **Gas unit convention:** Wei in raw blockchain data → Gwei in `block_analytics` table → Gwei in API responses → Gwei in frontend display
- **Chain identification:** String keys ("ethereum", "polygon", "arbitrum") used everywhere. `getChainDisplayName()` and `getChainColor()` utilities for UI.
- **Idempotent writes:** Unique constraint on (chain, block_number) prevents duplicates from reprocessed blocks.
- **Non-blocking analytics:** BlockAnalyticsService.persistBatch() catches exceptions to avoid blocking the indexing pipeline.
- **Batch processing:** Backfill mode processes blocks in batches of 50 with 10 worker threads. Incremental mode processes one block at a time.

---

## What Can Be Analyzed

With the `block_analytics` table, any SQL aggregation across these dimensions is possible:

**Dimensions:** chain, block_date, block_hour, block_timestamp, block_number
**Measures:** base_fee_gwei, avg/min/max_gas_price_gwei, gas_used, gas_limit, gas_used_percentage, transaction_count, tx_count_legacy, tx_count_eip1559, tx_count_contract, tx_count_failed, avg_gas_legacy, avg_gas_eip1559, avg_gas_contract

**Example analyses not yet implemented:**
- Weekly/monthly aggregation trends
- Gas price volatility (stddev) over time
- Block production rate analysis (gaps between timestamps)
- Transaction density heatmaps (hour × day-of-week)
- Gas price correlation between chains
- Peak usage detection (anomaly/spike identification)
- Moving averages and trend lines
- Block size analysis (gas_limit changes over time)
- Failed transaction rate trends
- Contract deployment frequency over time
- Gas efficiency metrics (avg gas per tx type over time)
- Chain comparison dashboards with normalized metrics
