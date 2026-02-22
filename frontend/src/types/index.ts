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

// ---- Historical Analytics Response Types ----

/** Response from GET /api/analytics/historical/gas-prices/daily */
export interface DailyGasPrice {
  chain: string;
  date: string;
  avgBaseFee: number | null;
  minBaseFee: number | null;
  maxBaseFee: number | null;
  avgGasPrice: number | null;
}

/** Response from GET /api/analytics/historical/gas-prices/hourly */
export interface HourlyGasPattern {
  chain: string;
  hour: number;
  avgBaseFee: number | null;
  avgGasPrice: number | null;
}

/** Response from GET /api/analytics/historical/block-fullness */
export interface BlockFullness {
  chain: string;
  avgFullness: number;
  minFullness: number;
  maxFullness: number;
  blockCount: number;
}

/** Response from GET /api/analytics/historical/block-fullness/daily */
export interface BlockFullnessDaily {
  chain: string;
  date: string;
  avgFullnessPercent: number | null;
}

/** Response from GET /api/analytics/historical/cross-chain */
export interface CrossChainComparison {
  chain: string;
  avgTxCount: number;
  avgGasPrice: number | null;
  avgBaseFee: number | null;
  totalTxs: number;
  blockCount: number;
}

/** Response from GET /api/analytics/historical/transaction-types */
export interface TransactionTypeAnalysis {
  chain: string;
  totalLegacy: number;
  totalEip1559: number;
  totalContract: number;
  totalFailed: number;
  avgGasLegacy: number | null;
  avgGasEip1559: number | null;
  avgGasContract: number | null;
}

/** Response from GET /api/analytics/historical/transaction-types/daily */
export interface DailyTransactionTypes {
  chain: string;
  date: string;
  totalLegacy: number;
  totalEip1559: number;
  totalContract: number;
  totalTxs: number;
}

/** Response from GET /api/analytics/historical/data-availability */
export interface DataAvailability {
  chain: string;
  earliestDate: string;
  latestDate: string;
  blockCount: number;
}

/** Response from GET /api/analytics/historical/gas-market */
export interface GasMarketDaily {
  chain: string;
  date: string;
  avgBaseFeeGwei: number | null;
  avgEffectiveGasPriceGwei: number | null;
  avgPriorityFeeGwei: number | null;
  minBaseFeeGwei: number | null;
  maxBaseFeeGwei: number | null;
}
