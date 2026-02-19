import { Play, Square, Clock, Activity } from "lucide-react";
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

  const handleStartAll = () => {
    for (const key of chainKeys) {
      startMutation.mutate({ chain: key, mode: "BACKFILL" });
    }
  };

  return (
    <Card className="flex flex-wrap items-center justify-between gap-4">
      <div className="flex items-center gap-4">
        <StatusBadge status={status.running ? "running" : "stopped"} />
        {status.running ? (
          <button
            onClick={() => stopMutation.mutate({})}
            disabled={stopMutation.isPending}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg bg-accent-red/10 text-accent-red border border-accent-red/20 hover:bg-accent-red/20 transition-colors disabled:opacity-50"
          >
            <Square className="w-3 h-3" />
            Stop All
          </button>
        ) : (
          <button
            onClick={handleStartAll}
            disabled={startMutation.isPending}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg bg-accent-green/10 text-accent-green border border-accent-green/20 hover:bg-accent-green/20 transition-colors disabled:opacity-50"
          >
            <Play className="w-3 h-3" />
            Start All
          </button>
        )}
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
        <MetricValue
          label="Uptime"
          value={status.uptime ?? "N/A"}
          icon={<Clock className="w-3 h-3" />}
        />
      </div>
    </Card>
  );
}
