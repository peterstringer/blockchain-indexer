import { useEffect, useRef, useState } from "react";
import { Square, RefreshCw, History, Zap, Signal } from "lucide-react";
import { Card } from "@/components/common/Card";
import { StatusBadge } from "@/components/common/StatusBadge";
import { useChainUpdates } from "@/hooks/useWebSocket";
import { useStartIndexing, useStopIndexing } from "@/hooks/useIndexerStatus";
import type { ChainStatus } from "@/types";
import { isRpcHealthObject } from "@/types";
import {
  formatNumber,
  formatCompact,
  formatRate,
  formatDuration,
  getChainColor,
  getChainDisplayName,
  getChainIcon,
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

  // Speed metric flash + coverage bar glow
  const [pulsing, setPulsing] = useState(false);
  const [glowKey, setGlowKey] = useState(0);
  const lastSeenBlockRef = useRef<string | null>(null);

  const currentBlock = progress?.currentBlock ?? chain.lastBlock ?? 0;
  const latestBlock = progress?.latestBlock ?? chain.targetBlock ?? 0;
  const bps = progress?.blocksPerSecond ?? chain.blocksPerSecond ?? 0;
  const color = getChainColor(chainKey);
  const stopped = !isRunning;

  // Backfill progress (prefer WebSocket, fall back to REST)
  const backfillFloor = progress?.backfillFloorBlock ?? chain.backfillFloorBlock ?? null;
  const backfillTarget = progress?.backfillTargetBlock ?? chain.backfillTargetBlock ?? null;
  const backfillComplete = progress?.reverseBackfillComplete ?? chain.reverseBackfillComplete ?? false;

  // Coverage bar calculations — bar represents entire chain (genesis to head)
  const startBlock = 0;
  const totalRange = latestBlock > startBlock ? latestBlock - startBlock : 1;
  const indexedLeft = backfillFloor != null ? backfillFloor : currentBlock;
  const indexedRight = currentBlock;
  const leftGreyPct = Math.max(0, Math.min(100, ((indexedLeft - startBlock) / totalRange) * 100));
  const greenPct = Math.max(0, Math.min(100 - leftGreyPct, ((indexedRight - indexedLeft) / totalRange) * 100));
  const totalIndexed = Math.max(0, indexedRight - indexedLeft);
  const coveragePct = totalRange > 0 ? Math.min(100, (totalIndexed / totalRange) * 100) : 0;

  // Watch for new blocks — trigger speed metric flash + bar glow
  useEffect(() => {
    if (recentBlocks.length === 0) return;
    const head = recentBlocks[0]!;
    const headKey = `${head.chain}:${head.blockNumber}`;
    if (headKey === lastSeenBlockRef.current) return;

    // Speed metric flash
    setPulsing(true);
    const id = setTimeout(() => setPulsing(false), 400);

    // Coverage bar glow — increment key to restart CSS animation
    setGlowKey((k) => k + 1);

    lastSeenBlockRef.current = headKey;
    return () => clearTimeout(id);
  }, [recentBlocks]);

  // Lag calculation (live feed)
  const lagBlocks = Math.max(0, latestBlock - currentBlock);
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
  const rpcStateStr = typeof chain.rpcHealth === "string" ? chain.rpcHealth : "";
  const isChainActive = rpcStateStr.startsWith("RUNNING");
  const rpcDotClass =
    wsTotal > 0
      ? wsHealthy === wsTotal
        ? "bg-accent-green"
        : wsHealthy > 0
          ? "bg-accent-amber"
          : "bg-accent-red"
      : isChainActive
        ? "bg-accent-green"
        : "bg-text-muted";
  const rpcLabel =
    wsTotal > 0
      ? wsHealthy === wsTotal
        ? `${wsHealthy}/${wsTotal} healthy`
        : wsHealthy > 0
          ? `${wsHealthy}/${wsTotal} degraded`
          : `${wsHealthy}/${wsTotal} down`
      : isChainActive
        ? "Connected"
        : "Idle";

  // Determine chain mode from rpcHealth string
  const isBackfilling = rpcStateStr === "RUNNING_BACKFILL" || rpcStateStr === "RUNNING_BOTH";
  const isIncremental = rpcStateStr === "RUNNING_INCREMENTAL" || rpcStateStr === "RUNNING_BOTH";
  const isBoth = rpcStateStr === "RUNNING_BOTH";
  const isLive = isIncremental && lagBlocks < 10;
  const isWaiting = isIncremental && bps === 0 && lagBlocks === 0;

  // Backfill ETA
  const backfillRemaining =
    backfillFloor != null && backfillTarget != null ? backfillFloor - backfillTarget : 0;
  const backfillEtaMs =
    bps > 0 && backfillRemaining > 0 ? (backfillRemaining / bps) * 1000 : 0;

  const chainStatus = isWaiting ? "running" : isLive ? "running" : isRunning && bps > 0 ? "running" : isRunning ? "info" : "stopped";
  const statusLabel = stopped
    ? "Idle"
    : isWaiting
      ? "Live — Waiting"
      : isBoth
        ? "Syncing + Backfilling"
        : isLive
          ? "Live"
          : isBackfilling
            ? "Backfilling"
            : "Syncing";

  return (
    <Card className="flex flex-col" hover={!stopped}>
      {/* Header: chain icon + name + status */}
      <div className="flex items-center justify-between mb-2">
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
        <StatusBadge status={chainStatus} size="sm" label={statusLabel} />
      </div>
      {/* Controls row */}
      <div className="flex items-center gap-1.5 mb-3">
        {/* Stop button — shown when anything is running */}
        {isRunning && (
          <button
            onClick={() => stopMutation.mutate({ chain: chainKey })}
            disabled={stopMutation.isPending}
            className="flex items-center gap-1 px-2 py-1 text-[11px] font-medium rounded-md bg-accent-red/10 text-accent-red border border-accent-red/20 hover:bg-accent-red/20 transition-colors disabled:opacity-50"
          >
            <Square className="w-3 h-3" />
            Stop
          </button>
        )}
        {/* Sync Latest — shown when incremental is not running */}
        {!isIncremental && (
          <button
            onClick={() => startMutation.mutate({ chain: chainKey, mode: "INCREMENTAL" })}
            disabled={startMutation.isPending}
            className="flex items-center gap-1 px-2 py-1 text-[11px] font-medium rounded-md bg-accent-green/10 text-accent-green border border-accent-green/20 hover:bg-accent-green/20 transition-colors disabled:opacity-50"
            title="Sync from last indexed block to chain tip"
          >
            <RefreshCw className="w-3 h-3" />
            Sync
          </button>
        )}
        {/* Backfill — shown when backfill is not currently running */}
        {!isBackfilling && (
          <button
            onClick={() => startMutation.mutate({ chain: chainKey, mode: "BACKFILL" })}
            disabled={startMutation.isPending}
            className="flex items-center gap-1 px-2 py-1 text-[11px] font-medium rounded-md bg-accent-blue/10 text-accent-blue border border-accent-blue/20 hover:bg-accent-blue/20 transition-colors disabled:opacity-50"
            title="Backfill historical blocks in reverse"
          >
            <History className="w-3 h-3" />
            Backfill
          </button>
        )}
      </div>

      {/* Coverage bar */}
      <div className="mb-4">
        <div className="flex items-center justify-between mb-1">
          <span className="text-[11px] text-text-muted">
            {formatCompact(totalIndexed)} / {formatCompact(totalRange)} blocks
          </span>
          <span className="text-xs font-mono text-text-secondary">
            {coveragePct.toFixed(1)}%
          </span>
        </div>
        <div className="relative w-full h-4 bg-bg-primary rounded-sm overflow-hidden">
          {/* Colored indexed segment */}
          <div
            className={`absolute top-0 bottom-0 transition-all duration-500 ease-out${stopped ? " opacity-50" : ""}`}
            style={{
              left: `${leftGreyPct}%`,
              width: `${greenPct}%`,
              backgroundColor: color,
            }}
          />
          {/* Glow overlay — fades in/out on new blocks, keyed to restart animation */}
          {!stopped && glowKey > 0 && (
            <div
              key={glowKey}
              className="absolute top-0 bottom-0 bar-glow-overlay pointer-events-none"
              style={{
                left: `${leftGreyPct}%`,
                width: `${greenPct}%`,
                backgroundColor: "rgba(255, 255, 255, 0.35)",
              }}
            />
          )}
        </div>
        <div className="flex justify-between mt-1 h-4">
          <span className="text-[10px] text-text-muted font-mono">
            0
          </span>
          {isBackfilling && backfillEtaMs > 0 && !stopped ? (
            <span className="text-[10px] text-text-muted">
              ~{formatDuration(backfillEtaMs)} remaining
            </span>
          ) : backfillComplete ? (
            <span className="text-[10px] text-accent-green">Backfill complete</span>
          ) : stopped && backfillFloor != null && !backfillComplete ? (
            <span className="text-[10px] text-text-muted">Backfill paused</span>
          ) : (
            <span className="text-[10px] text-text-muted">&nbsp;</span>
          )}
          <span className="text-[10px] text-text-muted font-mono">
            {formatCompact(latestBlock)}
          </span>
        </div>
      </div>

      {/* Metrics row: throughput + lag + RPC */}
      <div className="grid grid-cols-3 gap-3 min-h-[44px]">
        {/* Throughput */}
        <div className="flex flex-col gap-0.5">
          <span className="text-[11px] text-text-muted flex items-center gap-1">
            <Zap className="w-3 h-3" />
            Speed
          </span>
          {stopped ? (
            <span className="text-sm font-semibold font-mono text-text-muted">Stopped</span>
          ) : (
            <span className={`text-sm font-semibold font-mono text-text-primary inline-block whitespace-nowrap${pulsing ? " metric-pulse" : ""}`}>
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
          ) : isLive || lagBlocks === 0 ? (
            <span className="text-sm font-semibold font-mono text-accent-green">
              Caught up
            </span>
          ) : (
            <span className={`text-sm font-semibold font-mono ${lagColorClass}`}>
              {formatNumber(lagBlocks)} blocks
            </span>
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
