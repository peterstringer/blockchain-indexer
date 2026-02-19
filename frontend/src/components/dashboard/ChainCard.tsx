import { useState } from "react";
import {
  Layers,
  ArrowUpDown,
  Zap,
  Shield,
  Play,
  Square,
  ChevronDown,
  ChevronUp,
  Hash,
  Clock,
} from "lucide-react";
import { Card, CardHeader } from "@/components/common/Card";
import { ProgressBar } from "@/components/common/ProgressBar";
import { StatusBadge } from "@/components/common/StatusBadge";
import { MetricValue } from "@/components/common/MetricValue";
import { useChainUpdates } from "@/hooks/useWebSocket";
import { useStartIndexing, useStopIndexing } from "@/hooks/useIndexerStatus";
import type { ChainStatus } from "@/types";
import { isRpcHealthObject } from "@/types";
import {
  formatNumber,
  formatBlock,
  formatRate,
  progressPercent,
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
  const [expanded, setExpanded] = useState(false);
  const { progress, rpcHealth } = useChainUpdates(chainKey);
  const startMutation = useStartIndexing();
  const stopMutation = useStopIndexing();

  const currentBlock = progress?.currentBlock ?? chain.lastBlock ?? 0;
  const target = progress?.latestBlock ?? chain.targetBlock ?? 0;
  const bps = progress?.blocksPerSecond ?? chain.blocksPerSecond ?? 0;
  const percent = progressPercent(currentBlock, target);
  const color = getChainColor(chainKey);
  const eta = progress?.estimatedTimeRemaining ?? null;

  const rpcObj = isRpcHealthObject(chain.rpcHealth) ? chain.rpcHealth : null;
  const healthyProviders = rpcObj?.healthyProviders ?? 0;
  const totalProviders = rpcObj?.totalProviders ?? 0;

  const chainStatus = isRunning && bps > 0 ? "running" : isRunning ? "info" : "stopped";

  return (
    <Card className="flex flex-col">
      <CardHeader
        title=""
        action={
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
        }
      />

      {/* Chain title with icon */}
      <div className="flex items-center gap-3 -mt-3 mb-3">
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

      <ProgressBar percent={percent} color={color} eta={eta} />

      <div className="grid grid-cols-2 gap-4 mt-4">
        <MetricValue
          label="Current Block"
          value={formatBlock(currentBlock)}
          icon={<Layers className="w-3 h-3" />}
        />
        <MetricValue
          label="Target Block"
          value={formatBlock(target)}
          icon={<Layers className="w-3 h-3" />}
        />
        <MetricValue
          label="Blocks Indexed"
          value={formatNumber(chain.blocksIndexed)}
          icon={<ArrowUpDown className="w-3 h-3" />}
        />
        <MetricValue
          label="Throughput"
          value={formatRate(bps)}
          icon={<Zap className="w-3 h-3" />}
        />
      </div>

      {/* Expand toggle */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex items-center justify-center gap-1 mt-4 pt-3 border-t border-border/50 text-xs text-text-muted hover:text-text-secondary transition-colors"
      >
        {expanded ? (
          <>
            <ChevronUp className="w-3.5 h-3.5" />
            Less details
          </>
        ) : (
          <>
            <ChevronDown className="w-3.5 h-3.5" />
            More details
          </>
        )}
      </button>

      {/* Expanded details */}
      {expanded && (
        <div className="mt-3 space-y-3 animate-in fade-in duration-200">
          <div className="grid grid-cols-2 gap-3">
            <MetricValue
              label="Transactions"
              value={formatNumber(chain.transactionsIndexed)}
              icon={<Hash className="w-3 h-3" />}
            />
            <MetricValue
              label="RPC Providers"
              value={`${healthyProviders}/${totalProviders}`}
              icon={<Shield className="w-3 h-3" />}
            />
          </div>

          {/* RPC provider details from WebSocket */}
          {rpcHealth && rpcHealth.providerStates.length > 0 && (
            <div className="space-y-1.5">
              <span className="text-[11px] text-text-muted font-medium uppercase tracking-wider">
                RPC Providers
              </span>
              {rpcHealth.providerStates.map((p) => (
                <div
                  key={p.urlHash}
                  className="flex items-center justify-between text-xs bg-bg-primary/50 rounded-lg px-3 py-1.5"
                >
                  <div className="flex items-center gap-2">
                    <span
                      className={`w-1.5 h-1.5 rounded-full ${
                        p.state === "CLOSED"
                          ? "bg-accent-green"
                          : p.state === "HALF_OPEN"
                            ? "bg-accent-amber"
                            : "bg-accent-red"
                      }`}
                    />
                    <span className="font-mono text-text-muted">
                      ...{p.urlHash.slice(-8)}
                    </span>
                  </div>
                  <span className="text-text-muted capitalize">
                    {p.state.toLowerCase().replace("_", " ")}
                  </span>
                </div>
              ))}
            </div>
          )}

          {!rpcObj && (
            <div className="text-xs text-text-muted">
              RPC: {String(chain.rpcHealth)}
            </div>
          )}

          {progress && (
            <div className="flex items-center gap-1.5 text-xs text-text-muted">
              <Clock className="w-3 h-3" />
              Last update: {new Date(progress.timestamp).toLocaleTimeString()}
            </div>
          )}
        </div>
      )}
    </Card>
  );
}
