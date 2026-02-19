# Multi-Chain Block Indexer

Production-grade blockchain indexer that fetches blocks from multiple EVM chains via RPC, processes them concurrently, and exports to Parquet format.

> **Work in Progress** - This project is under active development.

## Tech Stack

- **Java 25** / **Spring Boot 4.0.2**
- **Web3j** for Ethereum JSON-RPC
- **Apache Parquet** for columnar data export
- **PostgreSQL** for persistent storage
- **Testcontainers** for integration testing
- **React** dashboard (planned)

## Supported Chains

- Ethereum
- Polygon
- Arbitrum
- Optimism
- Base

## Getting Started

```bash
# Copy environment file and configure RPC URLs
cp .env.example .env

# Build
./mvnw clean package

# Run
./mvnw spring-boot:run
```
