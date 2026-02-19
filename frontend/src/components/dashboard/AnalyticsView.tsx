import { MultiChainThroughputChart } from "@/components/charts/ThroughputChart";
import { GasPriceChart } from "@/components/charts/GasPriceChart";
import { TransactionVolumeChart } from "@/components/charts/TransactionVolumeChart";
import { GasChart } from "@/components/charts/GasChart";
import { useChainUpdates, useProgressHistory } from "@/hooks/useWebSocket";
import type { IndexerStatus, BlockIndexedMessage, IndexerProgressMessage } from "@/types";

interface AnalyticsViewProps {
  status: IndexerStatus;
}

export function AnalyticsView({ status }: AnalyticsViewProps) {
  const chainKeys = Object.keys(status.chains);

  // Collect data from all chains
  const blocksByChain: Record<string, BlockIndexedMessage[]> = {};
  const historyByChain: Record<string, IndexerProgressMessage[]> = {};

  for (const key of chainKeys) {
    const { recentBlocks } = useChainUpdates(key);
    blocksByChain[key] = recentBlocks;
    historyByChain[key] = useProgressHistory(key);
  }

  return (
    <div className="space-y-6">
      {/* Combined throughput */}
      <MultiChainThroughputChart
        historyByChain={historyByChain}
        chainKeys={chainKeys}
      />

      {/* Gas prices + transaction volume side by side */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <GasPriceChart blocksByChain={blocksByChain} chainKeys={chainKeys} />
        <TransactionVolumeChart
          blocksByChain={blocksByChain}
          chainKeys={chainKeys}
        />
      </div>

      {/* Per-chain gas charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {chainKeys.map((key) => (
          <GasChart key={key} chain={key} blocks={blocksByChain[key] ?? []} />
        ))}
      </div>
    </div>
  );
}
