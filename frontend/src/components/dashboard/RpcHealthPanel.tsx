import { formatDistanceToNow } from "date-fns";
import { Shield, ShieldCheck, ShieldAlert, ShieldX, Clock } from "lucide-react";
import { Card, CardHeader } from "@/components/common/Card";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { IndexerStatus, RpcHealthMessage } from "@/types";
import { isRpcHealthObject } from "@/types";
import { getChainDisplayName } from "@/utils/format";

interface RpcHealthPanelProps {
  healthMessages: Map<string, RpcHealthMessage>;
  status?: IndexerStatus;
}

export function RpcHealthPanel({ healthMessages, status }: RpcHealthPanelProps) {
  const wsEntries = Array.from(healthMessages.entries());

  // If we have detailed WebSocket data, show that
  if (wsEntries.length > 0) {
    return (
      <Card>
        <CardHeader
          title="RPC Provider Health"
          subtitle="Circuit breaker status per chain"
          action={<Shield className="w-4 h-4 text-text-muted" />}
        />
        <div className="space-y-4">
          {wsEntries.map(([chain, health]) => (
            <DetailedChainHealth key={chain} chain={chain} health={health} />
          ))}
        </div>
      </Card>
    );
  }

  // Fall back to REST status summary
  const chainKeys = status ? Object.keys(status.chains) : [];

  return (
    <Card>
      <CardHeader
        title="RPC Provider Health"
        subtitle="Circuit breaker status per chain"
        action={<Shield className="w-4 h-4 text-text-muted" />}
      />
      {chainKeys.length === 0 ? (
        <p className="text-xs text-text-muted py-4 text-center">
          No health data yet
        </p>
      ) : (
        <div className="space-y-3">
          {chainKeys.map((chain) => {
            const chainStatus = status!.chains[chain]!;
            const rpc = chainStatus.rpcHealth;
            const rpcObj = isRpcHealthObject(rpc) ? rpc : null;
            const rpcStr = typeof rpc === "string" ? rpc : "";
            const isActive = rpcStr.startsWith("RUNNING");

            const healthy = rpcObj?.healthyProviders ?? (isActive ? 1 : 0);
            const total = rpcObj?.totalProviders ?? (isActive ? 1 : 0);

            const overallStatus =
              total === 0
                ? ("stopped" as const)
                : healthy === total
                  ? ("healthy" as const)
                  : healthy > 0
                    ? ("degraded" as const)
                    : ("down" as const);

            const statusLabel =
              total === 0
                ? "Idle"
                : healthy === total
                  ? "All Healthy"
                  : healthy > 0
                    ? "Degraded"
                    : "Down";

            return (
              <div key={chain} className="border-b border-border/50 pb-3 last:border-0">
                <div className="flex items-center justify-between mb-1.5">
                  <span className="text-sm font-medium text-text-primary">
                    {getChainDisplayName(chain)}
                  </span>
                  <StatusBadge status={overallStatus === "stopped" ? "stopped" : overallStatus === "healthy" ? "running" : overallStatus === "degraded" ? "info" : "error"} size="sm" label={statusLabel} />
                </div>
                <div className="flex items-center gap-3 text-[11px]">
                  <div className="flex items-center gap-1.5">
                    {overallStatus === "healthy" ? (
                      <ShieldCheck className="w-3.5 h-3.5 text-accent-green" />
                    ) : overallStatus === "degraded" ? (
                      <ShieldAlert className="w-3.5 h-3.5 text-accent-amber" />
                    ) : overallStatus === "down" ? (
                      <ShieldX className="w-3.5 h-3.5 text-accent-red" />
                    ) : (
                      <Shield className="w-3.5 h-3.5 text-text-muted" />
                    )}
                    <span className="text-text-muted">
                      {total > 0 ? `${healthy}/${total} providers healthy` : "No providers"}
                    </span>
                  </div>
                  {rpcStr && (
                    <span className="text-text-muted font-mono text-[10px]">
                      {rpcStr.replace("RUNNING_", "").replace("_", " ")}
                    </span>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </Card>
  );
}

/** Detailed view when full WebSocket health data is available */
function DetailedChainHealth({ chain, health }: { chain: string; health: RpcHealthMessage }) {
  const overallStatus =
    health.providersHealthy === health.providersTotal
      ? "healthy" as const
      : health.providersHealthy > 0
        ? "degraded" as const
        : "down" as const;

  return (
    <div className="border-b border-border/50 pb-3 last:border-0">
      <div className="flex items-center justify-between mb-2">
        <span className="text-sm font-medium text-text-primary">
          {getChainDisplayName(chain)}
        </span>
        <StatusBadge status={overallStatus} size="sm" />
      </div>

      <div className="flex items-center gap-2 mb-2 text-[11px] text-text-muted">
        <span>{health.providersHealthy} healthy</span>
        <span>/</span>
        <span>{health.providersTotal} total</span>
      </div>

      <div className="space-y-1.5">
        {health.providerStates.map((p) => {
          const stateIcon =
            p.state === "CLOSED" ? (
              <ShieldCheck className="w-3.5 h-3.5 text-accent-green" />
            ) : p.state === "HALF_OPEN" ? (
              <ShieldAlert className="w-3.5 h-3.5 text-accent-amber" />
            ) : (
              <ShieldX className="w-3.5 h-3.5 text-accent-red" />
            );

          const stateLabel =
            p.state === "CLOSED"
              ? "Healthy"
              : p.state === "HALF_OPEN"
                ? "Recovering"
                : "Circuit Open";

          return (
            <div
              key={p.urlHash}
              className="bg-bg-primary/50 rounded-lg px-3 py-2"
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  {stateIcon}
                  <span className="font-mono text-xs text-text-muted">
                    ...{p.urlHash.slice(-8)}
                  </span>
                </div>
                <span
                  className={`text-[11px] font-medium ${
                    p.state === "CLOSED"
                      ? "text-accent-green"
                      : p.state === "HALF_OPEN"
                        ? "text-accent-amber"
                        : "text-accent-red"
                  }`}
                >
                  {stateLabel}
                </span>
              </div>
              <div className="flex items-center gap-4 mt-1.5 text-[11px]">
                <span className="text-accent-green">
                  {p.successCount.toLocaleString()} ok
                </span>
                <span className="text-accent-red">
                  {p.failureCount.toLocaleString()} fail
                </span>
              </div>
            </div>
          );
        })}
      </div>

      <div className="flex items-center gap-1 mt-2 text-[10px] text-text-muted">
        <Clock className="w-3 h-3" />
        Updated{" "}
        {formatDistanceToNow(new Date(health.timestamp), {
          addSuffix: true,
        })}
      </div>
    </div>
  );
}
