import { OverviewBar } from "./OverviewBar";
import { ChainCard } from "./ChainCard";
import { BlockFeed } from "./BlockFeed";
import { RpcHealthPanel } from "./RpcHealthPanel";
import { ThroughputChart } from "@/components/charts/ThroughputChart";
import { GasChart } from "@/components/charts/GasChart";
import { useChainUpdates, useProgressHistory } from "@/hooks/useWebSocket";
import type { IndexerStatus, BlockIndexedMessage, RpcHealthMessage } from "@/types";

interface DashboardProps {
  status: IndexerStatus;
}

export function Dashboard({ status }: DashboardProps) {
  const chainKeys = Object.keys(status.chains);

  return (
    <div className="space-y-6">
      <OverviewBar status={status} />

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {chainKeys.map((key) => (
          <ChainCard
            key={key}
            chainKey={key}
            chain={status.chains[key]!}
            isRunning={status.running}
          />
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {chainKeys.map((key) => (
          <ChainCharts key={key} chainKey={key} />
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <div className="lg:col-span-2">
          <AggregatedBlockFeed chainKeys={chainKeys} />
        </div>
        <AggregatedRpcHealth chainKeys={chainKeys} />
      </div>
    </div>
  );
}

function ChainCharts({ chainKey }: { chainKey: string }) {
  const { recentBlocks } = useChainUpdates(chainKey);
  const history = useProgressHistory(chainKey);

  return (
    <>
      <ThroughputChart chain={chainKey} history={history} />
      <GasChart chain={chainKey} blocks={recentBlocks} />
    </>
  );
}

/**
 * Hooks called in .map() is safe here because chainKeys is derived from
 * backend config and its length/order never changes between renders.
 */
function AggregatedBlockFeed({ chainKeys }: { chainKeys: string[] }) {
  const allBlocks: BlockIndexedMessage[] = [];

  const chainData = chainKeys.map((key) => useChainUpdates(key).recentBlocks);

  for (const blocks of chainData) {
    allBlocks.push(...blocks);
  }

  allBlocks.sort(
    (a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()
  );

  return <BlockFeed blocks={allBlocks} />;
}

function AggregatedRpcHealth({ chainKeys }: { chainKeys: string[] }) {
  const healthMap = new Map<string, RpcHealthMessage>();

  for (const key of chainKeys) {
    const { rpcHealth } = useChainUpdates(key);
    if (rpcHealth) {
      healthMap.set(key, rpcHealth);
    }
  }

  return <RpcHealthPanel healthMessages={healthMap} />;
}
