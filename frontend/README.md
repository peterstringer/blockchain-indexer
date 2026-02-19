# Blockchain Indexer Dashboard

React frontend for the Multi-Chain Block Indexer. Provides real-time monitoring of blockchain indexing progress via WebSocket and REST APIs.

## Features

- **Live Progress Tracking** - Real-time block indexing progress per chain via STOMP WebSocket
- **Throughput Charts** - Blocks/second over time using Recharts
- **Gas Usage Charts** - Per-block gas consumption visualization
- **RPC Health Monitoring** - Circuit breaker state for each RPC provider
- **Recent Blocks Feed** - Live table of recently indexed blocks across all chains
- **Start/Stop Controls** - Start and stop indexing from the dashboard

## Tech Stack

- **React 19** + TypeScript
- **Vite** - Build tool and dev server
- **Tailwind CSS v4** - Utility-first styling with dark theme
- **Recharts** - Charting library
- **TanStack Query** - Server state management
- **@stomp/stompjs** + SockJS - WebSocket client
- **Lucide React** - Icons
- **date-fns** - Date formatting

## Setup

```bash
# Install dependencies
npm install

# Start development server (proxies to backend on :8080)
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview
```

## Development

The Vite dev server runs on port 3000 and proxies API requests:

| Frontend Path | Backend Target |
|---|---|
| `/api/*` | `http://localhost:8080/api/*` |
| `/ws` | `ws://localhost:8080/ws` |

Start the backend first:

```bash
# From project root
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

Then start the frontend:

```bash
cd frontend
npm run dev
```

Open http://localhost:3000 in your browser.

## Project Structure

```
src/
├── components/
│   ├── layout/        # Header, Layout wrapper
│   ├── dashboard/     # OverviewBar, ChainCard, RecentBlocks, RpcHealthPanel
│   ├── charts/        # ThroughputChart, GasChart
│   └── common/        # Card, StatusBadge, ProgressBar, MetricValue
├── hooks/             # useIndexerStatus, useWebSocket
├── services/          # API client, WebSocket service
├── types/             # TypeScript interfaces matching backend DTOs
├── utils/             # Formatting helpers
├── App.tsx            # Main application component
└── main.tsx           # Entry point with QueryClient setup
```

## WebSocket Topics

The dashboard subscribes to these STOMP topics:

| Topic | Data |
|---|---|
| `/topic/indexer/{chain}/progress` | Indexing progress (block counts, throughput, ETA) |
| `/topic/indexer/{chain}/blocks` | Individual block details as they're indexed |
| `/topic/indexer/{chain}/rpc-health` | RPC provider circuit breaker states |
| `/topic/indexer/status` | Global indexer status changes |
