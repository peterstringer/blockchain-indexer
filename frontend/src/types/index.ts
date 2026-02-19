/** Matches backend IndexerStatus response from GET /api/indexer/status */
export interface IndexerStatus {
  running: boolean;
  mode: "STOPPED" | "BACKFILL" | "INCREMENTAL";
  chains: Record<string, ChainStatus>;
  uptime?: string;
  startedAt?: string | null;
}

export interface ChainStatus {
  chainId?: number;
  lastBlock: number;
  targetBlock?: number;
  blocksIndexed: number;
  transactionsIndexed: number;
  blocksPerSecond: number;
  /** Can be an object { totalProviders, healthyProviders } or a string like "NOT_STARTED" */
  rpcHealth: RpcHealth | string;
}

export interface RpcHealth {
  totalProviders: number;
  healthyProviders: number;
}

/** Type guard: checks if rpcHealth is the object form */
export function isRpcHealthObject(v: RpcHealth | string): v is RpcHealth {
  return typeof v === "object" && v !== null && "totalProviders" in v;
}

/** Matches backend IndexerProgressMessage WebSocket DTO */
export interface IndexerProgressMessage {
  chain: string;
  currentBlock: number;
  latestBlock: number;
  blocksProcessed: number;
  transactionsProcessed: number;
  blocksPerSecond: number;
  estimatedTimeRemaining: string | null;
  timestamp: string;
}

/** Matches backend BlockIndexedMessage WebSocket DTO */
export interface BlockIndexedMessage {
  chain: string;
  blockNumber: number;
  blockHash: string;
  transactionCount: number;
  gasUsed: number;
  baseFeeGwei: number | null;
  timestamp: string;
}

/** Matches backend RpcHealthMessage WebSocket DTO */
export interface RpcHealthMessage {
  chain: string;
  providersTotal: number;
  providersHealthy: number;
  providerStates: ProviderState[];
  timestamp: string;
}

export interface ProviderState {
  urlHash: string;
  state: "CLOSED" | "OPEN" | "HALF_OPEN";
  successCount: number;
  failureCount: number;
}

/** Request body for POST /api/indexer/start */
export interface StartIndexingRequest {
  chain?: string;
  mode?: "BACKFILL" | "INCREMENTAL";
}

/** Request body for POST /api/indexer/stop */
export interface StopIndexingRequest {
  chain?: string;
}

/** Response from GET /api/indexer/health */
export interface HealthResponse {
  status: string;
  timestamp: number;
  demoMode: boolean;
  running: boolean;
  chainsConfigured: number;
}

/** Checkpoint entity from GET /api/indexer/checkpoints */
export interface IndexerCheckpoint {
  id: number;
  chain: string;
  lastIndexedBlock: number;
  totalBlocksIndexed: number;
  totalTransactionsIndexed: number;
  lastUpdated: string | null;
  createdAt: string | null;
}

/** Response from GET /api/analytics/gas-prices */
export interface GasPriceAggregation {
  chain: string;
  avgGasPrice: number;
  minGasPrice: number;
  maxGasPrice: number;
  blockCount: number;
}
