import { useMemo } from "react";
import { useIndexerStatus } from "./useIndexerStatus";
import { useChainUpdates, useProgressHistory } from "./useWebSocket";
import type { ChainStatus, IndexerProgressMessage, BlockIndexedMessage, RpcHealthMessage } from "@/types";
import { progressPercent } from "@/utils/format";

interface ChainData {
  chainKey: string;
  chain: ChainStatus;
  progress: IndexerProgressMessage | null;
  recentBlocks: BlockIndexedMessage[];
  rpcHealth: RpcHealthMessage | null;
  history: IndexerProgressMessage[];
  currentBlock: number;
  targetBlock: number;
  percent: number;
  bps: number;
}

/** Combines REST status and WebSocket real-time data for a specific chain */
export function useChainData(chainKey: string): ChainData | null {
  const { data: status } = useIndexerStatus();
  const { progress, recentBlocks, rpcHealth } = useChainUpdates(chainKey);
  const history = useProgressHistory(chainKey);

  return useMemo(() => {
    const chain = status?.chains[chainKey];
    if (!chain) return null;

    const currentBlock = progress?.currentBlock ?? chain.lastBlock ?? 0;
    const targetBlock = progress?.latestBlock ?? chain.targetBlock ?? 0;
    const bps = progress?.blocksPerSecond ?? chain.blocksPerSecond ?? 0;
    const percent = progressPercent(currentBlock, targetBlock);

    return {
      chainKey,
      chain,
      progress,
      recentBlocks,
      rpcHealth,
      history,
      currentBlock,
      targetBlock,
      percent,
      bps,
    };
  }, [chainKey, status, progress, recentBlocks, rpcHealth, history]);
}

/** Returns all chain keys from the status response */
export function useChainKeys(): string[] {
  const { data: status } = useIndexerStatus();
  return useMemo(() => (status ? Object.keys(status.chains) : []), [status]);
}
