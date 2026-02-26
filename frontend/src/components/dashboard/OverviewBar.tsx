import { Square, RefreshCw, History, Activity, Database } from "lucide-react";
import { Card } from "@/components/common/Card";
import { StatusBadge } from "@/components/common/StatusBadge";
import { MetricValue } from "@/components/common/MetricValue";
import { useStartIndexing, useStopIndexing } from "@/hooks/useIndexerStatus";
import type { IndexerStatus } from "@/types";
import { formatNumber } from "@/utils/format";

interface OverviewBarProps {
  status: IndexerStatus;
}

export function OverviewBar({ status }: OverviewBarProps) {
  const startMutation = useStartIndexing();
  const stopMutation = useStopIndexing();

  const chainKeys = Object.keys(status.chains);
  const chains = Object.values(status.chains);
  const totalBlocks = chains.reduce((sum, c) => sum + (c.blocksIndexed ?? 0), 0);
  const totalTxs = chains.reduce((sum, c) => sum + (c.transactionsIndexed ?? 0), 0);
  const totalBps = chains.reduce((sum, c) => sum + (c.blocksPerSecond ?? 0), 0);

  // Aggregate backfill progress across chains
  const backfillingChains = chains.filter(
    (c) => typeof c.rpcHealth === "string" && (c.rpcHealth === "RUNNING_BACKFILL" || c.rpcHealth === "RUNNING_BOTH") && c.backfillProgress != null
  );
  const avgBackfillProgress =
    backfillingChains.length > 0
      ? backfillingChains.reduce((sum, c) => sum + (c.backfillProgress ?? 0), 0) / backfillingChains.length
      : null;

  const handleSyncAll = () => {
    for (const key of chainKeys) {
      startMutation.mutate({ chain: key, mode: "INCREMENTAL" });
    }
  };

  const handleBackfillAll = () => {
    for (const key of chainKeys) {
      startMutation.mutate({ chain: key, mode: "BACKFILL" });
    }
  };

  return (
    <Card className="flex flex-wrap items-center justify-between gap-4">
      <div className="flex items-center gap-3">
        <StatusBadge status={status.running ? "running" : "stopped"} />
        {status.running && (
          <button
            onClick={() => stopMutation.mutate({})}
            disabled={stopMutation.isPending}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg bg-accent-red/10 text-accent-red border border-accent-red/20 hover:bg-accent-red/20 transition-colors disabled:opacity-50"
          >
            <Square className="w-3 h-3" />
            Stop All
          </button>
        )}
        <button
          onClick={handleSyncAll}
          disabled={startMutation.isPending}
          className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg bg-accent-green/10 text-accent-green border border-accent-green/20 hover:bg-accent-green/20 transition-colors disabled:opacity-50"
        >
          <RefreshCw className="w-3 h-3" />
          Sync All
        </button>
        <button
          onClick={handleBackfillAll}
          disabled={startMutation.isPending}
          className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg bg-accent-blue/10 text-accent-blue border border-accent-blue/20 hover:bg-accent-blue/20 transition-colors disabled:opacity-50"
        >
          <History className="w-3 h-3" />
          Backfill All
        </button>
      </div>
      <div className="flex items-center gap-8">
        <MetricValue
          label="Blocks Indexed"
          value={formatNumber(totalBlocks)}
          icon={<Activity className="w-3 h-3" />}
        />
        <MetricValue
          label="Transactions"
          value={formatNumber(totalTxs)}
          icon={<Activity className="w-3 h-3" />}
        />
        <MetricValue
          label="Throughput"
          value={`${totalBps.toFixed(1)}/s`}
          icon={<Activity className="w-3 h-3" />}
        />
        {avgBackfillProgress != null && (
          <MetricValue
            label="Backfill"
            value={`${avgBackfillProgress.toFixed(1)}%`}
            icon={<Database className="w-3 h-3" />}
          />
        )}
      </div>
    </Card>
  );
}
