<p align="center">
  <img src="https://img.icons8.com/color/96/blockchain-technology.png" alt="Blockchain Indexer" width="80"/>
</p>

<h1 align="center">Multi-Chain Block Indexer</h1>

<p align="center">
  A blockchain indexer that fetches blocks from multiple EVM chains via JSON-RPC, processes them concurrently, and exports to Apache Parquet for analytics.
</p>

<p align="center">
  <a href="https://github.com/peterstringer/blockchain-indexer/actions/workflows/ci.yml"><img src="https://github.com/peterstringer/blockchain-indexer/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/peterstringer/blockchain-indexer/actions/workflows/codeql.yml"><img src="https://github.com/peterstringer/blockchain-indexer/actions/workflows/codeql.yml/badge.svg" alt="CodeQL"></a>
  <a href="https://github.com/peterstringer/blockchain-indexer/releases"><img src="https://img.shields.io/github/v/release/peterstringer/blockchain-indexer?include_prereleases&label=release" alt="Release"></a>
  <a href="https://github.com/peterstringer/blockchain-indexer/pkgs/container/blockchain-indexer"><img src="https://img.shields.io/badge/ghcr.io-blockchain--indexer-blue?logo=docker" alt="Docker"></a>
  <img src="https://img.shields.io/badge/Java-25-orange?logo=openjdk" alt="Java 25">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.0.2-6DB33F?logo=springboot" alt="Spring Boot">
  <img src="https://img.shields.io/badge/license-PolyForm%20NC%201.0-blue" alt="License">
</p>

---

## Overview

The Multi-Chain Block Indexer connects to EVM-compatible blockchains via JSON-RPC, fetches blocks and transactions concurrently, persists indexing progress to PostgreSQL for crash recovery, writes analytical data to Apache Parquet files, and streams real-time updates to a React dashboard over WebSocket.

### Key Features

- **Multi-chain indexing** - Ethereum, Polygon, and Arbitrum with independent indexing loops
- **Two indexing modes** - Backfill (historical ranges) and Incremental (live tail with reorg detection)
- **Circuit breaker + rate limiting** - Per-provider health tracking with automatic failover
- **Apache Parquet export** - Columnar storage with SNAPPY compression and chain/date partitioning
- **Crash-safe checkpoints** - Atomic checkpoint updates in PostgreSQL for resume-on-restart
- **Real-time dashboard** - React + WebSocket with live throughput charts, gas analytics, and RPC health
- **Demo mode** - Deterministic synthetic data generation without RPC or database dependencies
- **Prometheus metrics** - Micrometer integration with `/actuator/prometheus` endpoint

---

## Why This Project

This project demonstrates the kind of data infrastructure work done at companies like **Dune Analytics** - ingesting, transforming, and storing blockchain data at scale for analytical queries.

### Skills Demonstrated

| Area | Details |
|------|---------|
| **Concurrent systems** | Two-pool executor model, batch processing, thread-safe buffering |
| **Distributed systems patterns** | Circuit breaker state machine, rate limiting, crash recovery, reorg detection |
| **Data engineering** | Parquet columnar storage, Avro schemas, partition strategies, compression |
| **Full-stack development** | Spring Boot REST API + React/TypeScript dashboard + WebSocket real-time |
| **Production readiness** | Docker multi-stage builds, CI/CD, health checks, Prometheus metrics, Flyway migrations |
| **Testing** | 129 unit tests, Testcontainers integration tests, synthetic data generation |

### Technologies

**Backend:** Java 25, Spring Boot 4.0.2, Web3j, Apache Parquet, Apache Avro, Hadoop, PostgreSQL, Flyway, Lombok

**Frontend:** React 19, TypeScript, Vite, Tailwind CSS, TanStack React Query, Recharts, STOMP/SockJS

**Infrastructure:** Docker, GitHub Actions, Testcontainers, Prometheus/Micrometer

---

## Architecture

```mermaid
graph TB
    subgraph RPC["RPC Providers"]
        A1["Alchemy"]
        A2["Infura"]
        A3["Public RPCs"]
    end

    subgraph Indexer["Block Indexer Service"]
        RC["RPC Client<br/><i>Circuit Breaker + Rate Limiting</i>"]
        BI["Block Indexer<br/><i>Backfill / Incremental</i>"]
        SD["Synthetic Data Provider<br/><i>Demo Mode</i>"]
    end

    subgraph Storage["Storage"]
        PG[("PostgreSQL<br/><i>Checkpoints + Metrics</i>")]
        PQ[("Parquet Files<br/><i>Blocks + Transactions</i>")]
    end

    subgraph Frontend["React Dashboard"]
        WS["WebSocket<br/><i>STOMP over SockJS</i>"]
        UI["Dashboard UI<br/><i>Charts + Controls</i>"]
    end

    A1 & A2 & A3 --> RC
    RC --> BI
    SD -.->|demo mode| BI
    BI --> PG
    BI --> PQ
    BI --> WS
    WS --> UI

    style RPC fill:#f9f,stroke:#333
    style Indexer fill:#bbf,stroke:#333
    style Storage fill:#bfb,stroke:#333
    style Frontend fill:#fbb,stroke:#333
```

### Component Overview

| Component | Responsibility |
|-----------|---------------|
| **BlockIndexerService** | Orchestrates chain lifecycle, manages backfill/incremental loops, coordinates concurrency |
| **RpcClientService** | Web3j client management, circuit breaker state machine, token-bucket rate limiting, round-robin load balancing |
| **ParquetWriterService** | Buffers records in memory, flushes to timestamped Parquet files with configurable partitioning |
| **WebSocketService** | Broadcasts progress, block notifications, and RPC health changes with per-topic throttling |
| **SyntheticDataProvider** | Generates deterministic, realistic block/transaction data for demo mode |
| **CheckpointRepository** | Persists last-indexed block per chain for crash recovery |

### Concurrency Model

Two thread pools prevent deadlocks between chain loops and block fetching:

```
chainExecutor (cached, unbounded)          fetchExecutor (fixed, 10 threads)
├── ethereum-loop ─── batch ──────────────► fetch block 100 ┐
├── polygon-loop  ─── batch ──────────────► fetch block 101 ├── parallel
└── arbitrum-loop ─── batch ──────────────► fetch block 102 ┘
```

### Circuit Breaker

```
CLOSED ──(5 consecutive failures)──► OPEN
  ▲                                    │
  │                               (30s timeout)
  │                                    ▼
  └──────(success)──────── HALF_OPEN ◄─┘
```

Each RPC provider is tracked independently. When all providers for a chain are OPEN, the least-recently-failed provider is promoted to HALF_OPEN for a probe request.

---

## Features

### Multi-Chain Support

Each chain runs an independent indexing loop with its own:
- RPC provider pool with failover
- Rate limiter (configurable requests/second)
- Checkpoint for crash recovery
- Parquet output partitions

Currently supported: **Ethereum** (chain ID 1), **Polygon** (chain ID 137), **Arbitrum** (chain ID 42161).

### Indexing Modes

**Backfill** - Index a historical block range:
- Processes blocks in configurable batch sizes (default 50)
- Concurrent fetching within each batch
- Displays ETA based on throughput
- Stops at `end-block` or chain head

**Incremental** - Live tail with reorg detection:
- Polls for new blocks every 5 seconds
- Compares `parentHash` to detect chain reorganizations
- On reorg: rolls back checkpoint and re-indexes from fork point

### Parquet Export

Blocks and transactions are written to separate Parquet files with:
- **SNAPPY compression** for fast read/write
- **Chain partitioning** (`output/ethereum/`, `output/polygon/`)
- **Date partitioning** (`output/ethereum/2026-02-20/`)
- **128 MB row groups** and **1 MB page sizes**
- **In-memory buffering** with automatic flush at 1000 records

### Real-Time Dashboard

The React dashboard provides:
- **Overview bar** with aggregate statistics
- **Chain cards** with per-chain status, throughput, and start/stop controls
- **Throughput chart** showing blocks/second over time
- **Gas price chart** with base fee and utilization trends
- **Live block feed** streaming recently indexed blocks
- **RPC health panel** showing circuit breaker state per provider
- **Analytics tab** with gas price aggregation queries and block/tx lookup
- **Settings tab** for checkpoint management and Parquet statistics

### Demo Mode

Run the full application without RPC providers or PostgreSQL:
- Generates deterministic synthetic data (same seed = same output)
- Realistic gas economics with daily cycles and congestion spikes
- Configurable block count and random seed
- Uses H2 in-memory database

---

## Quick Start

### Docker (recommended)

```bash
git clone https://github.com/peterstringer/blockchain-indexer.git
cd blockchain-indexer

# Configure API keys
cp .env.example .env
# Edit .env with your Alchemy/Infura keys

# Start the full stack
docker compose up -d

# Open the dashboard
open http://localhost:8080
```

### Demo Mode (no API keys needed)

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d
```

### Local Development

```bash
# Start PostgreSQL only
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres

# Build and run the frontend
cd frontend && npm ci && npm run build && cd ..

# Start the application
mvn spring-boot:run
```

### Prerequisites

- **Java 25** (Eclipse Temurin recommended)
- **Node.js 20** (for frontend build)
- **Docker** (for PostgreSQL and containerized deployment)
- **Maven 3.9+**

---

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `ALCHEMY_ETH_URL` | Alchemy Ethereum RPC | `https://eth.llamarpc.com` |
| `ALCHEMY_POLYGON_URL` | Alchemy Polygon RPC | `https://polygon.llamarpc.com` |
| `ALCHEMY_ARBITRUM_URL` | Alchemy Arbitrum RPC | `https://arb1.arbitrum.io/rpc` |
| `INFURA_ETH_URL` | Infura Ethereum RPC | `https://ethereum-rpc.publicnode.com` |
| `INFURA_POLYGON_URL` | Infura Polygon RPC | `https://polygon-bor-rpc.publicnode.com` |
| `INFURA_ARBITRUM_URL` | Infura Arbitrum RPC | `https://arbitrum-one-rpc.publicnode.com` |
| `DATABASE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/indexer` |
| `DATABASE_USERNAME` | Database user | `postgres` |
| `DATABASE_PASSWORD` | Database password | `postgres` |
| `PARQUET_OUTPUT_PATH` | Output directory for Parquet files | `./output` |
| `DEMO_MODE` | Enable synthetic data mode | `false` |

### Chain Configuration

Each chain is configured in `application.yml` under `indexer.chains`:

```yaml
indexer:
  chains:
    ethereum:
      name: Ethereum
      chain-id: 1
      rpc-urls:
        - ${ALCHEMY_ETH_URL}
        - ${INFURA_ETH_URL}
      start-block: 21000000       # First block to index
      end-block:                  # null = no limit (for incremental)
      max-retries-per-block: 3    # Exponential backoff retries
      retry-delay-ms: 1000       # Initial retry delay
      rate-limit-requests-per-second: 10
```

### Concurrency Tuning

```yaml
indexer:
  concurrency:
    worker-threads: 10    # Parallel block fetch threads
    batch-size: 50        # Blocks per batch in backfill mode
    max-queue-size: 500   # Internal queue depth
```

### Parquet Output

```yaml
indexer:
  parquet:
    output-path: ./output
    compression-codec: SNAPPY     # SNAPPY, GZIP, UNCOMPRESSED
    partition-by-chain: true      # output/ethereum/, output/polygon/
    partition-by-date: true       # output/ethereum/2026-02-20/
    row-group-size: 134217728     # 128 MB
    page-size: 1048576            # 1 MB
```

---

## API Documentation

Interactive API docs are available at **http://localhost:8080/swagger-ui.html** when the application is running.

### Indexer Control

```bash
# Start backfill indexing for Ethereum
curl -X POST http://localhost:8080/api/indexer/start \
  -H 'Content-Type: application/json' \
  -d '{"chain": "ethereum", "mode": "BACKFILL"}'

# Start incremental (live tail) for Polygon
curl -X POST http://localhost:8080/api/indexer/start \
  -H 'Content-Type: application/json' \
  -d '{"chain": "polygon", "mode": "INCREMENTAL"}'

# Stop a specific chain
curl -X POST http://localhost:8080/api/indexer/stop \
  -H 'Content-Type: application/json' \
  -d '{"chain": "ethereum"}'

# Stop all chains
curl -X POST http://localhost:8080/api/indexer/stop \
  -H 'Content-Type: application/json' -d '{}'
```

### Status & Health

```bash
# Overall status
curl http://localhost:8080/api/indexer/status

# Single chain status
curl http://localhost:8080/api/indexer/status/ethereum

# Health check (for container orchestrators)
curl http://localhost:8080/api/indexer/health

# Operational metrics
curl http://localhost:8080/api/indexer/metrics
```

### Checkpoints

```bash
# List all checkpoints
curl http://localhost:8080/api/indexer/checkpoints

# Reset checkpoint (requires confirmation)
curl -X POST 'http://localhost:8080/api/indexer/checkpoints/ethereum/reset?confirm=true'
```

### Analytics

```bash
# Gas price aggregation for a block range (max 1000 blocks)
curl 'http://localhost:8080/api/analytics/gas-prices?chain=ethereum&fromBlock=21000000&toBlock=21000100'

# Block lookup
curl http://localhost:8080/api/analytics/blocks/ethereum/21000000

# Transaction search (across all chains)
curl http://localhost:8080/api/analytics/transactions/0xabc123...
```

---

## Dashboard

The React dashboard runs at **http://localhost:8080** (served by Spring Boot) or **http://localhost:3000** (Vite dev server with hot reload).

### Dashboard Tab
- **Overview bar** - Total blocks/transactions indexed, overall running state
- **Chain cards** - Per-chain status with live throughput, start/stop controls
- **Throughput chart** - Blocks/second over time per chain (Recharts)
- **Gas chart** - Base fee and gas utilization trends
- **Block feed** - Live stream of recently indexed blocks
- **RPC health panel** - Circuit breaker state per provider

### Analytics Tab
- Gas price aggregation queries with interactive charts
- Block detail lookup by chain and number
- Transaction search by hash across all chains

### Settings Tab
- Start/stop individual chains or all at once
- Toggle between BACKFILL and INCREMENTAL modes
- View and reset checkpoints
- Parquet writer statistics (buffered records, files written)

---

## Querying Parquet Data

The Parquet files are compatible with any columnar query engine.

### DuckDB

```sql
-- Install and load
INSTALL parquet;
LOAD parquet;

-- Query blocks
SELECT chain, block_number, transaction_count, base_fee_per_gas / 1e9 AS base_fee_gwei
FROM read_parquet('output/ethereum/2026-02-20/blocks_*.parquet')
ORDER BY block_number DESC
LIMIT 20;

-- Gas analytics across chains
SELECT chain,
       COUNT(*) AS blocks,
       AVG(gas_used_percentage) AS avg_utilization,
       AVG(base_fee_per_gas / 1e9) AS avg_base_fee_gwei
FROM read_parquet('output/*/blocks_*.parquet')
GROUP BY chain;

-- Large transactions
SELECT chain, tx_hash, from_address, to_address, value / 1e18 AS eth_value
FROM read_parquet('output/ethereum/*/transactions_*.parquet')
WHERE value > 10e18
ORDER BY value DESC
LIMIT 10;
```

### Python / pandas

```python
import pandas as pd

# Read all Ethereum blocks
df = pd.read_parquet("output/ethereum/", engine="pyarrow")

# Gas utilization over time
df["base_fee_gwei"] = df["base_fee_per_gas"] / 1e9
print(df[["block_number", "transaction_count", "gas_used_percentage", "base_fee_gwei"]].describe())
```

---

## Development

### Running Tests

```bash
# Unit tests only (129 tests)
mvn test

# Unit + integration tests (requires Docker for Testcontainers)
mvn verify
```

### Project Structure

```
blockchain-indexer/
├── src/main/java/com/peterstringer/blockchain/indexer/
│   ├── config/          # Spring configuration and properties binding
│   ├── controller/      # REST API endpoints (IndexerController, AnalyticsController)
│   ├── model/           # Domain objects (IndexedBlock, IndexedTransaction)
│   ├── repository/      # JPA repositories (Checkpoint, Metrics, RpcHealth)
│   └── service/         # Core services (BlockIndexer, RpcClient, ParquetWriter, WebSocket)
├── src/main/resources/
│   ├── db/migration/    # Flyway SQL migrations (V1-V3)
│   └── application.yml  # Application configuration
├── src/test/
│   ├── java/.../         # Unit tests (*Test.java) and integration tests (*IT.java)
│   └── resources/        # Test profiles (application-integration.yml)
├── frontend/             # React/TypeScript dashboard
│   ├── src/
│   │   ├── components/   # UI components (charts, cards, panels)
│   │   ├── pages/        # Dashboard, Analytics, Settings pages
│   │   ├── hooks/        # Custom React hooks (WebSocket, API)
│   │   └── services/     # API client and WebSocket service
│   └── vite.config.ts
├── docker/               # Docker entrypoint script
├── Dockerfile            # Multi-stage build (Node + Maven + JRE)
├── docker-compose.yml    # Production stack
├── docker-compose.dev.yml    # Dev override (PostgreSQL only)
└── docker-compose.demo.yml   # Demo mode override
```

### Database Schema

Managed by Flyway with 3 migrations:

| Table | Purpose |
|-------|---------|
| `indexer_checkpoints` | Last indexed block per chain for crash recovery |
| `indexer_metrics` | Time-series operational metrics (blocks/sec, error rates) |
| `rpc_provider_health` | Circuit breaker state per RPC provider |

---

## Performance

Throughput depends on RPC provider latency and rate limits.

| Configuration | Throughput |
|---------------|-----------|
| Alchemy free tier, 10 threads, batch 50 | ~40-60 blocks/sec (Ethereum) |
| Public RPCs, 4 threads, batch 10 | ~10-20 blocks/sec |
| Demo mode (synthetic data) | ~500+ blocks/sec |

### Optimization Tips

- Increase `worker-threads` if your RPC provider allows higher rate limits
- Use Alchemy or Infura paid tiers for higher throughput
- Set `partition-by-date: true` for better query pruning in DuckDB/Spark
- Use SNAPPY compression (default) for the best read/write balance

---

## Troubleshooting

| Issue | Solution |
|-------|---------|
| `Connection to localhost:5432 refused` | Start PostgreSQL: `docker compose up -d postgres` |
| `Rate limit exceeded` on RPC | Reduce `rate-limit-requests-per-second` in config |
| All RPC providers showing OPEN | Check API keys in `.env`; providers auto-recover after 30s |
| Parquet write errors on JDK 25 | Known Hadoop compatibility issue; buffering continues, files written on flush |
| `Port 8080 already in use` | Stop other services or change `server.port` in `application.yml` |
| Integration tests failing | Ensure Docker is running (required for Testcontainers) |
| Frontend not loading | Build frontend first: `cd frontend && npm ci && npm run build` |

---

## CI/CD

GitHub Actions workflows:

| Workflow | Trigger | What It Does |
|----------|---------|-------------|
| **CI** | Push to main, PRs | Build, unit tests, integration tests, frontend build, Docker image |
| **Release** | `v*` tags | JAR artifact, GitHub Release, versioned Docker image to ghcr.io |
| **CodeQL** | Push to main, weekly | Security vulnerability scanning for Java |
| **Dependabot** | Weekly | Dependency update PRs for Maven, npm, Actions, Docker |

---

## License

This project is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE).

Copyright &copy; 2026 Peter Stringer

You may use this software for non-commercial purposes including research, personal study, hobby projects, and educational use.

---

## Acknowledgments

- [Web3j](https://github.com/web3j/web3j) - Java library for Ethereum JSON-RPC
- [Apache Parquet](https://parquet.apache.org/) - Columnar storage format
- [Spring Boot](https://spring.io/projects/spring-boot) - Application framework
- [Recharts](https://recharts.org/) - React charting library
- [Testcontainers](https://testcontainers.com/) - Docker-based integration testing

---

<p align="center">
  Built by <a href="https://github.com/peterstringer">Peter Stringer</a>
</p>
