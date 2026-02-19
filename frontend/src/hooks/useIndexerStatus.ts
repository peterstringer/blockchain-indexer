import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { fetchStatus, startIndexing, stopIndexing } from "@/services/api";
import type { StartIndexingRequest, StopIndexingRequest } from "@/types";

export function useIndexerStatus() {
  return useQuery({
    queryKey: ["indexer-status"],
    queryFn: fetchStatus,
    refetchInterval: 5000,
  });
}

export function useStartIndexing() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: StartIndexingRequest) => startIndexing(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["indexer-status"] });
    },
  });
}

export function useStopIndexing() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: StopIndexingRequest) => stopIndexing(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["indexer-status"] });
    },
  });
}
