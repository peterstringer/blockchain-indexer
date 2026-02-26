import type {
  IndexerStatus,
  HealthResponse,
  StartIndexingRequest,
  StopIndexingRequest,
  IndexerCheckpoint,
  ChainConfig,
  BlockFullnessDaily,
  DailyTransactionTypes,
  DailyFailureRate,
  TxDensityCell,
  DataAvailability,
  GasMarketDaily,
  ExportMetadata,
} from "@/types";

const BASE = "/api";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...init,
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`${res.status}: ${body}`);
  }
  return res.json() as Promise<T>;
}

/** GET /api/indexer/status */
export function fetchStatus(): Promise<IndexerStatus> {
  return request("/indexer/status");
}

/** GET /api/indexer/health */
export function fetchHealth(): Promise<HealthResponse> {
  return request("/indexer/health");
}

/** POST /api/indexer/start */
export function startIndexing(body: StartIndexingRequest = {}): Promise<IndexerStatus> {
  return request("/indexer/start", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/** POST /api/indexer/stop */
export function stopIndexing(body: StopIndexingRequest = {}): Promise<IndexerStatus> {
  return request("/indexer/stop", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/** GET /api/indexer/checkpoints */
export function fetchCheckpoints(): Promise<IndexerCheckpoint[]> {
  return request("/indexer/checkpoints");
}

/** GET /api/indexer/config */
export function fetchConfig(): Promise<ChainConfig[]> {
  return request("/indexer/config");
}

/** PUT /api/indexer/config/chains/{chain}/start-block */
export function updateStartBlock(chain: string, startBlock: number): Promise<Record<string, unknown>> {
  return request(`/indexer/config/chains/${encodeURIComponent(chain)}/start-block`, {
    method: "PUT",
    body: JSON.stringify({ startBlock }),
  });
}

// ---- Historical Analytics API ----

/** GET /api/analytics/historical/block-fullness/daily */
export function fetchDailyBlockFullness(
  from: string, to: string, chain?: string
): Promise<BlockFullnessDaily[]> {
  const params = new URLSearchParams({ from, to });
  if (chain) params.set("chain", chain);
  return request(`/analytics/historical/block-fullness/daily?${params}`);
}

/** GET /api/analytics/historical/transaction-types/daily */
export function fetchDailyTransactionTypes(
  from: string, to: string, chain?: string
): Promise<DailyTransactionTypes[]> {
  const params = new URLSearchParams({ from, to });
  if (chain) params.set("chain", chain);
  return request(`/analytics/historical/transaction-types/daily?${params}`);
}

/** GET /api/analytics/historical/failure-rate */
export function fetchDailyFailureRate(
  from: string, to: string, chain?: string
): Promise<DailyFailureRate[]> {
  const params = new URLSearchParams({ from, to });
  if (chain) params.set("chain", chain);
  return request(`/analytics/historical/failure-rate?${params}`);
}

/** GET /api/analytics/historical/tx-density-heatmap */
export function fetchTxDensityHeatmap(
  from: string, to: string, chain?: string
): Promise<TxDensityCell[]> {
  const params = new URLSearchParams({ from, to });
  if (chain) params.set("chain", chain);
  return request(`/analytics/historical/tx-density-heatmap?${params}`);
}

/** GET /api/analytics/historical/data-availability */
export function fetchDataAvailability(): Promise<DataAvailability[]> {
  return request("/analytics/historical/data-availability");
}

/** GET /api/analytics/historical/gas-market */
export function fetchGasMarket(
  from: string, to: string, chain?: string
): Promise<GasMarketDaily[]> {
  const params = new URLSearchParams({ from, to });
  if (chain) params.set("chain", chain);
  return request(`/analytics/historical/gas-market?${params}`);
}

// ---- Export API ----

/** GET /api/export/metadata */
export function fetchExportMetadata(): Promise<ExportMetadata> {
  return request("/export/metadata");
}

/** Build the download URL for block analytics export */
export function buildExportUrl(params: {
  from: string;
  to: string;
  chain?: string;
  format: "csv" | "parquet";
  columns?: string[];
}): string {
  const qs = new URLSearchParams({ from: params.from, to: params.to, format: params.format });
  if (params.chain) qs.set("chain", params.chain);
  if (params.columns && params.columns.length > 0) {
    for (const c of params.columns) {
      qs.append("columns", c);
    }
  }
  return `${BASE}/export/block-analytics?${qs}`;
}
