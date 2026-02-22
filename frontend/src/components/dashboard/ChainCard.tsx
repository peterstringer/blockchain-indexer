import { useEffect, useRef, useState } from "react";
import { Play, Square, Zap, Signal } from "lucide-react";
import { Card } from "@/components/common/Card";
import { StatusBadge } from "@/components/common/StatusBadge";
import { useChainUpdates } from "@/hooks/useWebSocket";
import { useStartIndexing, useStopIndexing } from "@/hooks/useIndexerStatus";
import type { ChainStatus } from "@/types";
import { isRpcHealthObject } from "@/types";
import {
  formatNumber,
  formatRate,
  formatDuration,
  progressPercent,
  getChainColor,
  getChainDisplayName,
  getChainIcon,
  getChainBlockTimeMs,
} from "@/utils/format";

interface ChainCardProps {
  chainKey: string;
  chain: ChainStatus;
  isRunning: boolean;
}

export function ChainCard({ chainKey, chain, isRunning }: ChainCardProps) {
  const { progress, recentBlocks, rpcHealth } = useChainUpdates(chainKey);
  const startMutation = useStartIndexing();
  const stopMutation = useStopIndexing();

  // Pulse animation: fire when a new block arrives
  const [pulsing, setPulsing] = useState(false);
  const blockCountRef = useRef(recentBlocks.length);

  useEffect(() => {
    if (recentBlocks.length > blockCountRef.current) {
      setPulsing(true);
      const id = setTimeout(() => setPulsing(false), 400);
      blockCountRef.current = recentBlocks.length;
      return () => clearTimeout(id);
    }
    blockCountRef.current = recentBlocks.length;
  }, [recentBlocks.length]);

  const currentBlock = progress?.currentBlock ?? chain.lastBlock ?? 0;
  const latestBlock = progress?.latestBlock ?? chain.targetBlock ?? 0;
  const bps = progress?.blocksPerSecond ?? chain.blocksPerSecond ?? 0;
  const percent = progressPercent(currentBlock, latestBlock);
  const color = getChainColor(chainKey);
  const stopped = !isRunning;

  // Lag calculation
  const lagBlocks = Math.max(0, latestBlock - currentBlock);
  const lagMs = lagBlocks * getChainBlockTimeMs(chainKey);
  const lagColorClass =
    lagBlocks < 10
      ? "text-accent-green"
      : lagBlocks <= 100
        ? "text-accent-amber"
        : "text-accent-red";

  // RPC health (prefer WebSocket data, fall back to REST status)
  const rpcObj = isRpcHealthObject(chain.rpcHealth) ? chain.rpcHealth : null;
  const wsHealthy = rpcHealth?.providersHealthy ?? rpcObj?.healthyProviders ?? 0;
  const wsTotal = rpcHealth?.providersTotal ?? rpcObj?.totalProviders ?? 0;
  const rpcDotClass =
    wsTotal === 0
      ? "bg-accent-amber"
      : wsHealthy === wsTotal
        ? "bg-accent-green"
        : wsHealthy > 0
          ? "bg-accent-amber"
          : "bg-accent-red";
  const rpcLabel =
    wsTotal === 0
      ? "No providers"
      : wsHealthy === wsTotal
        ? `${wsHealthy}/${wsTotal} healthy`
        : wsHealthy > 0
          ? `${wsHealthy}/${wsTotal} degraded`
          : `${wsHealthy}/${wsTotal} down`;

  const chainStatus = isRunning && bps > 0 ? "running" : isRunning ? "info" : "stopped";

  return (
    <Card className={`flex flex-col${stopped ? " opacity-60 grayscale-[30%]" : ""}`} hover={!stopped}>
      {/* Header: chain icon + name + status + controls */}
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-3">
          <div
            className="w-9 h-9 rounded-lg flex items-center justify-center text-white font-bold text-sm"
            style={{ backgroundColor: color }}
          >
            {getChainIcon(chainKey)}
          </div>
          <div>
            <h3 className="text-sm font-semibold text-text-primary">
              {getChainDisplayName(chainKey)}
            </h3>
            {chain.chainId != null && (
              <span className="text-xs text-text-muted">Chain ID: {chain.chainId}</span>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <StatusBadge status={chainStatus} size="sm" label={isRunning ? "Indexing" : "Idle"} />
          {isRunning ? (
            <button
              onClick={() => stopMutation.mutate({ chain: chainKey })}
              disabled={stopMutation.isPending}
              className="flex items-center gap-1 px-2 py-1 text-[11px] font-medium rounded-md bg-accent-red/10 text-accent-red border border-accent-red/20 hover:bg-accent-red/20 transition-colors disabled:opacity-50"
            >
              <Square className="w-3 h-3" />
              Stop
            </button>
          ) : (
            <button
              onClick={() => startMutation.mutate({ chain: chainKey, mode: "BACKFILL" })}
              disabled={startMutation.isPending}
              className="flex items-center gap-1 px-2 py-1 text-[11px] font-medium rounded-md bg-accent-green/10 text-accent-green border border-accent-green/20 hover:bg-accent-green/20 transition-colors disabled:opacity-50"
            >
              <Play className="w-3 h-3" />
              Start
            </button>
          )}
        </div>
      </div>

      {/* Progress bar */}
      <div className="mb-4">
        <div className="flex justify-between mb-1">
          <span className="text-xs text-text-muted">
            {formatNumber(currentBlock)} / {formatNumber(latestBlock)}
          </span>
          <span className="text-xs font-mono text-text-secondary">
            {percent.toFixed(1)}%
          </span>
        </div>
        <div className="w-full h-2 bg-bg-primary rounded-full overflow-hidden">
          <div
            className="h-2 rounded-full transition-all duration-500 ease-out"
            style={{ width: `${Math.min(100, Math.max(0, percent))}%`, backgroundColor: color }}
          />
        </div>
      </div>

      {/* Metrics row: throughput + lag + RPC */}
      <div className="grid grid-cols-3 gap-3">
        {/* Throughput */}
        <div className="flex flex-col gap-0.5">
          <span className="text-[11px] text-text-muted flex items-center gap-1">
            <Zap className="w-3 h-3" />
            Speed
          </span>
          {stopped ? (
            <span className="text-sm font-semibold font-mono text-text-muted">Stopped</span>
          ) : (
            <span className={`text-sm font-semibold font-mono text-text-primary inline-block${pulsing ? " metric-pulse" : ""}`}>
              {formatRate(bps)}
            </span>
          )}
        </div>

        {/* Lag to chain tip */}
        <div className="flex flex-col gap-0.5">
          <span className="text-[11px] text-text-muted flex items-center gap-1">
            <Signal className="w-3 h-3" />
            Lag
          </span>
          {stopped ? (
            <span className="text-sm font-semibold font-mono text-text-muted">—</span>
          ) : (
            <>
              <span className={`text-sm font-semibold font-mono ${lagColorClass}`}>
                {formatNumber(lagBlocks)} blocks
              </span>
              <span className={`text-[10px] ${lagColorClass}`}>
                ~{formatDuration(lagMs)}
              </span>
            </>
          )}
        </div>

        {/* RPC health */}
        <div className="flex flex-col gap-0.5">
          <span className="text-[11px] text-text-muted">RPC</span>
          <div className="flex items-center gap-1.5">
            <span className={`w-2 h-2 rounded-full shrink-0 ${rpcDotClass}`} />
            <span className="text-xs text-text-secondary">{rpcLabel}</span>
          </div>
        </div>
      </div>
    </Card>
  );
}
