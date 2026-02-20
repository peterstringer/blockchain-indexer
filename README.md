# Multi-Chain Block Indexer

[![CI](https://github.com/peterstringer/blockchain-indexer/actions/workflows/ci.yml/badge.svg)](https://github.com/peterstringer/blockchain-indexer/actions/workflows/ci.yml)
[![CodeQL](https://github.com/peterstringer/blockchain-indexer/actions/workflows/codeql.yml/badge.svg)](https://github.com/peterstringer/blockchain-indexer/actions/workflows/codeql.yml)
[![Release](https://img.shields.io/github/v/release/peterstringer/blockchain-indexer?include_prereleases)](https://github.com/peterstringer/blockchain-indexer/releases)
[![Docker](https://img.shields.io/badge/ghcr.io-blockchain--indexer-blue)](https://github.com/peterstringer/blockchain-indexer/pkgs/container/blockchain-indexer)

Production-grade blockchain indexer that fetches blocks from multiple EVM chains via RPC, processes them concurrently, and exports to Parquet format.

> **Work in Progress** - This project is under active development.

## Tech Stack

- **Java 25** / **Spring Boot 4.0.2**
- **Web3j** for Ethereum JSON-RPC
- **Apache Parquet** for columnar data export
- **PostgreSQL** for persistent storage
- **React 19** / **Vite** / **Tailwind CSS** dashboard
- **Testcontainers** for integration testing

## Supported Chains

- Ethereum
- Polygon
- Arbitrum

## Getting Started

### Docker (recommended)

```bash
# Clone and configure
cp .env.example .env
# Edit .env with your Alchemy/Infura API keys

# Start everything
docker compose up -d

# Demo mode (no API keys needed)
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d
```

The dashboard is available at http://localhost:8080.

### Local Development

```bash
# Start PostgreSQL only
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres

# Install frontend dependencies and build
cd frontend && npm ci && npm run build && cd ..

# Run the application
mvn spring-boot:run
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `ALCHEMY_ETH_URL` | Alchemy Ethereum RPC endpoint | Public fallback |
| `ALCHEMY_POLYGON_URL` | Alchemy Polygon RPC endpoint | Public fallback |
| `ALCHEMY_ARBITRUM_URL` | Alchemy Arbitrum RPC endpoint | Public fallback |
| `INFURA_ETH_URL` | Infura Ethereum RPC endpoint | Public fallback |
| `INFURA_POLYGON_URL` | Infura Polygon RPC endpoint | Public fallback |
| `INFURA_ARBITRUM_URL` | Infura Arbitrum RPC endpoint | Public fallback |
| `DATABASE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/indexer` |
| `DEMO_MODE` | Enable synthetic demo data | `false` |

## Testing

```bash
# Unit tests only
mvn test

# Unit + integration tests (requires Docker)
mvn verify
```

## Creating a Release

```bash
git tag v0.1.0
git push origin v0.1.0
```

This triggers the release workflow which builds a JAR, creates a GitHub Release, and pushes a versioned Docker image to `ghcr.io`.

## Branch Protection

Recommended settings for the `main` branch:

- Require CI workflow to pass before merging
- Require at least 1 code review approval
- Do not allow direct pushes to main
- Require branches to be up to date before merging
