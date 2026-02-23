import { useQuery } from "@tanstack/react-query";
import {
  fetchDailyBlockFullness,
  fetchDailyTransactionTypes,
  fetchDailyFailureRate,
  fetchTxDensityHeatmap,
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

export function useDailyBlockFullness(from: string, to: string, chain?: string) {
  return useQuery({
    queryKey: ["historical", "block-fullness-daily", from, to, chain],
    queryFn: () => fetchDailyBlockFullness(from, to, chain),
    enabled: !!from && !!to,
    staleTime: 60_000,
  });
}

export function useDailyTransactionTypes(from: string, to: string, chain?: string) {
  return useQuery({
    queryKey: ["historical", "tx-types-daily", from, to, chain],
    queryFn: () => fetchDailyTransactionTypes(from, to, chain),
    enabled: !!from && !!to,
    staleTime: 60_000,
  });
}

export function useDailyFailureRate(from: string, to: string, chain?: string) {
  return useQuery({
    queryKey: ["historical", "failure-rate", from, to, chain],
    queryFn: () => fetchDailyFailureRate(from, to, chain),
    enabled: !!from && !!to,
    staleTime: 60_000,
  });
}

export function useTxDensityHeatmap(from: string, to: string, chain?: string) {
  return useQuery({
    queryKey: ["historical", "tx-density-heatmap", from, to, chain],
    queryFn: () => fetchTxDensityHeatmap(from, to, chain),
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
