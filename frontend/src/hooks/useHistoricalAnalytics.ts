import { useQuery } from "@tanstack/react-query";
import {
  fetchDailyGasPrices,
  fetchHourlyGasPatterns,
  fetchBlockFullness,
  fetchCrossChainComparison,
  fetchTransactionTypeAnalysis,
  fetchDataAvailability,
  fetchGasMarket,
} from "@/services/api";

export function useDataAvailability() {
  return useQuery({
    queryKey: ["historical", "data-availability"],
    queryFn: fetchDataAvailability,
    refetchInterval: 30_000,
  });
}

export function useDailyGasPrices(from: string, to: string, chain?: string) {
  return useQuery({
    queryKey: ["historical", "daily-gas", from, to, chain],
    queryFn: () => fetchDailyGasPrices(from, to, chain),
    enabled: !!from && !!to,
    staleTime: 60_000,
  });
}

export function useHourlyGasPatterns(from: string, to: string, chain?: string) {
  return useQuery({
    queryKey: ["historical", "hourly-gas", from, to, chain],
    queryFn: () => fetchHourlyGasPatterns(from, to, chain),
    enabled: !!from && !!to,
    staleTime: 60_000,
  });
}

export function useBlockFullness(from: string, to: string) {
  return useQuery({
    queryKey: ["historical", "block-fullness", from, to],
    queryFn: () => fetchBlockFullness(from, to),
    enabled: !!from && !!to,
    staleTime: 60_000,
  });
}

export function useCrossChainComparison(from: string, to: string) {
  return useQuery({
    queryKey: ["historical", "cross-chain", from, to],
    queryFn: () => fetchCrossChainComparison(from, to),
    enabled: !!from && !!to,
    staleTime: 60_000,
  });
}

export function useTransactionTypeAnalysis(from: string, to: string, chain?: string) {
  return useQuery({
    queryKey: ["historical", "tx-types", from, to, chain],
    queryFn: () => fetchTransactionTypeAnalysis(from, to, chain),
    enabled: !!from && !!to,
    staleTime: 60_000,
  });
}

export function useGasMarket(from: string, to: string, chain?: string) {
  return useQuery({
    queryKey: ["historical", "gas-market", from, to, chain],
    queryFn: () => fetchGasMarket(from, to, chain),
    enabled: !!from && !!to,
    staleTime: 60_000,
  });
}
