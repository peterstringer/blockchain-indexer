import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { fetchStatus, startIndexing, stopIndexing } from "@/services/api";
import { useToast } from "@/components/common/Toast";
import type { IndexerStatus, StartIndexingRequest, StopIndexingRequest } from "@/types";

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
    onMutate: (variables) => {
      // Optimistic update: immediately show chain as running
      queryClient.setQueryData<IndexerStatus>(["indexer-status"], (old) => {
        if (!old) return old;
        const chain = variables.chain;
        if (!chain || !old.chains[chain]) return old;
        return {
          ...old,
          running: true,
          mode: variables.mode ?? "BACKFILL",
          chains: {
            ...old.chains,
            [chain]: {
              ...old.chains[chain],
              rpcHealth: `RUNNING_${variables.mode ?? "BACKFILL"}`,
            },
          },
        };
      });
    },
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ["indexer-status"] });
      toast("success", `Started ${variables.mode?.toLowerCase() ?? ""} indexing for ${variables.chain ?? "all chains"}`);
    },
    onError: (error: Error, variables) => {
      // Roll back optimistic update
      queryClient.invalidateQueries({ queryKey: ["indexer-status"] });
      toast("error", `Failed to start ${variables.chain ?? "indexing"}: ${error.message}`);
    },
  });
}

export function useStopIndexing() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: (body: StopIndexingRequest) => stopIndexing(body),
    onMutate: (variables) => {
      // Optimistic update: immediately show chain as stopped
      queryClient.setQueryData<IndexerStatus>(["indexer-status"], (old) => {
        if (!old) return old;
        const chain = variables.chain;
        if (chain && old.chains[chain]) {
          return {
            ...old,
            chains: {
              ...old.chains,
              [chain]: { ...old.chains[chain], rpcHealth: "STOPPED" },
            },
          };
        }
        // Stop all — mark everything stopped
        const chains = { ...old.chains };
        for (const key of Object.keys(chains)) {
          chains[key] = { ...chains[key]!, rpcHealth: "STOPPED" };
        }
        return { ...old, running: false, mode: "STOPPED" as const, chains };
      });
    },
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ["indexer-status"] });
      toast("info", `Stopped indexing for ${variables.chain ?? "all chains"}`);
    },
    onError: (error: Error) => {
      queryClient.invalidateQueries({ queryKey: ["indexer-status"] });
      toast("error", `Failed to stop indexing: ${error.message}`);
    },
  });
}
