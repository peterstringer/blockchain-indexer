import { useState } from "react";
import { ChevronDown, ChevronUp, Settings } from "lucide-react";
import { OverviewBar } from "./OverviewBar";
import { ChainCard } from "./ChainCard";
import { BlockFeed } from "./BlockFeed";
import { RpcHealthPanel } from "./RpcHealthPanel";
import { useChainUpdates } from "@/hooks/useWebSocket";
import type { IndexerStatus, BlockIndexedMessage, RpcHealthMessage } from "@/types";

interface DashboardProps {
  status: IndexerStatus;
}

export function Dashboard({ status }: DashboardProps) {
  const chainKeys = Object.keys(status.chains);
  const [detailsOpen, setDetailsOpen] = useState(false);

  return (
    <div className="space-y-6">
      <OverviewBar status={status} />

      {/* Chain cards in a 3-column grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {chainKeys.map((key) => (
          <ChainColumn
            key={key}
            chainKey={key}
            chain={status.chains[key]!}
            isRunning={status.running}
          />
        ))}
      </div>

      {/* Collapsible System Details */}
      <div className="border border-border rounded-xl overflow-hidden">
        <button
          onClick={() => setDetailsOpen(!detailsOpen)}
          className="w-full flex items-center justify-between px-5 py-3 bg-bg-card hover:bg-bg-card-hover transition-colors"
        >
          <div className="flex items-center gap-2 text-sm text-text-secondary">
            <Settings className="w-4 h-4 text-text-muted" />
            System Details
          </div>
          {detailsOpen ? (
            <ChevronUp className="w-4 h-4 text-text-muted" />
          ) : (
            <ChevronDown className="w-4 h-4 text-text-muted" />
          )}
        </button>
        {detailsOpen && (
          <div className="p-4 bg-bg-card border-t border-border animate-in fade-in duration-200">
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
              <div className="lg:col-span-2">
                <AggregatedBlockFeed chainKeys={chainKeys} />
              </div>
              <AggregatedRpcHealth chainKeys={chainKeys} status={status} />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function ChainColumn({
  chainKey,
  chain,
  isRunning,
}: {
  chainKey: string;
  chain: IndexerStatus["chains"][string];
  isRunning: boolean;
}) {
  return <ChainCard chainKey={chainKey} chain={chain} isRunning={isRunning} />;
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

function AggregatedRpcHealth({ chainKeys, status }: { chainKeys: string[]; status: IndexerStatus }) {
  const healthMap = new Map<string, RpcHealthMessage>();

  for (const key of chainKeys) {
    const { rpcHealth } = useChainUpdates(key);
    if (rpcHealth) {
      healthMap.set(key, rpcHealth);
    }
  }

  return <RpcHealthPanel healthMessages={healthMap} status={status} />;
}
