import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { fetchStatus, startIndexing, stopIndexing } from "@/services/api";
import { useToast } from "@/components/common/Toast";
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
  const { toast } = useToast();

  return useMutation({
    mutationFn: (body: StartIndexingRequest) => startIndexing(body),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ["indexer-status"] });
      toast("success", `Started ${variables.mode?.toLowerCase() ?? ""} indexing for ${variables.chain ?? "all chains"}`);
    },
    onError: (error: Error, variables) => {
      toast("error", `Failed to start ${variables.chain ?? "indexing"}: ${error.message}`);
    },
  });
}

export function useStopIndexing() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: (body: StopIndexingRequest) => stopIndexing(body),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ["indexer-status"] });
      toast("info", `Stopped indexing for ${variables.chain ?? "all chains"}`);
    },
    onError: (error: Error) => {
      toast("error", `Failed to stop indexing: ${error.message}`);
    },
  });
}
