import type {
  IndexerStatus,
  HealthResponse,
  StartIndexingRequest,
  StopIndexingRequest,
  GasPriceAggregation,
  IndexerCheckpoint,
  DailyGasPrice,
  HourlyGasPattern,
  BlockFullness,
  BlockFullnessDaily,
  CrossChainComparison,
  TransactionTypeAnalysis,
  DataAvailability,
  GasMarketDaily,
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

/** GET /api/analytics/gas-prices?chain=... */
export function fetchGasPrices(chain?: string): Promise<GasPriceAggregation[]> {
  const params = chain ? `?chain=${encodeURIComponent(chain)}` : "";
  return request(`/analytics/gas-prices${params}`);
}

/** GET /api/indexer/checkpoints */
export function fetchCheckpoints(): Promise<IndexerCheckpoint[]> {
  return request("/indexer/checkpoints");
}

// ---- Historical Analytics API ----

/** GET /api/analytics/historical/gas-prices/daily */
export function fetchDailyGasPrices(
  from: string, to: string, chain?: string
): Promise<DailyGasPrice[]> {
  const params = new URLSearchParams({ from, to });
  if (chain) params.set("chain", chain);
  return request(`/analytics/historical/gas-prices/daily?${params}`);
}

/** GET /api/analytics/historical/gas-prices/hourly */
export function fetchHourlyGasPatterns(
  from: string, to: string, chain?: string
): Promise<HourlyGasPattern[]> {
  const params = new URLSearchParams({ from, to });
  if (chain) params.set("chain", chain);
  return request(`/analytics/historical/gas-prices/hourly?${params}`);
}

/** GET /api/analytics/historical/block-fullness */
export function fetchBlockFullness(
  from: string, to: string
): Promise<BlockFullness[]> {
  const params = new URLSearchParams({ from, to });
  return request(`/analytics/historical/block-fullness?${params}`);
}

/** GET /api/analytics/historical/block-fullness/daily */
export function fetchDailyBlockFullness(
  from: string, to: string, chain?: string
): Promise<BlockFullnessDaily[]> {
  const params = new URLSearchParams({ from, to });
  if (chain) params.set("chain", chain);
  return request(`/analytics/historical/block-fullness/daily?${params}`);
}

/** GET /api/analytics/historical/cross-chain */
export function fetchCrossChainComparison(
  from: string, to: string
): Promise<CrossChainComparison[]> {
  const params = new URLSearchParams({ from, to });
  return request(`/analytics/historical/cross-chain?${params}`);
}

/** GET /api/analytics/historical/transaction-types */
export function fetchTransactionTypeAnalysis(
  from: string, to: string, chain?: string
): Promise<TransactionTypeAnalysis[]> {
  const params = new URLSearchParams({ from, to });
  if (chain) params.set("chain", chain);
  return request(`/analytics/historical/transaction-types?${params}`);
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
