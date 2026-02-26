/** Matches backend IndexerStatus response from GET /api/indexer/status */
export interface IndexerStatus {
  running: boolean;
  mode: "STOPPED" | "BACKFILL" | "INCREMENTAL" | "RUNNING_BOTH";
  chains: Record<string, ChainStatus>;
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
  /** Reverse backfill progress as a percentage (0–100), null when not backfilling. */
  backfillProgress?: number | null;
  /** Lowest block reached during reverse backfill. */
  backfillFloorBlock?: number | null;
  /** Configured start block — the backfill target floor. */
  backfillTargetBlock?: number | null;
  /** Whether the reverse backfill has completed. */
  reverseBackfillComplete?: boolean | null;
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
  backfillProgress?: number | null;
  backfillFloorBlock?: number | null;
  backfillTargetBlock?: number | null;
  reverseBackfillComplete?: boolean | null;
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

/** Chain configuration from GET /api/indexer/config */
export interface ChainConfig {
  chain: string;
  name: string;
  chainId: number;
  startBlock: number;
  blockTimeMs: number;
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

// ---- Historical Analytics Response Types ----

/** Response from GET /api/analytics/historical/block-fullness/daily */
export interface BlockFullnessDaily {
  chain: string;
  date: string;
  avgFullnessPercent: number | null;
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

/** Response from GET /api/analytics/historical/failure-rate */
export interface DailyFailureRate {
  chain: string;
  date: string;
  totalTransactions: number;
  failedTransactions: number;
  failureRatePercent: number | null;
  avgGasPriceGwei: number | null;
}

/** Response from GET /api/analytics/historical/tx-density-heatmap */
export interface TxDensityCell {
  chain: string;
  dayOfWeek: number;
  hour: number;
  avgTransactionCount: number | null;
  totalBlocks: number;
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

// ---- Export Types ----

/** Response from GET /api/export/metadata */
export interface ExportMetadata {
  chains: string[];
  earliestDate: string | null;
  latestDate: string | null;
  totalRows: number;
  columns: ExportColumnDef[];
}

export interface ExportColumnDef {
  key: string;
  label: string;
  group: string;
}
