import { formatDistanceToNow } from "date-fns";
import { Shield, ShieldCheck, ShieldAlert, ShieldX, Clock } from "lucide-react";
import { Card, CardHeader } from "@/components/common/Card";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { RpcHealthMessage } from "@/types";
import { getChainDisplayName } from "@/utils/format";

interface RpcHealthPanelProps {
  healthMessages: Map<string, RpcHealthMessage>;
}

export function RpcHealthPanel({ healthMessages }: RpcHealthPanelProps) {
  const entries = Array.from(healthMessages.entries());

  return (
    <Card>
      <CardHeader
        title="RPC Provider Health"
        subtitle="Circuit breaker status per chain"
        action={<Shield className="w-4 h-4 text-text-muted" />}
      />
      {entries.length === 0 ? (
        <p className="text-xs text-text-muted py-4 text-center">
          No health data yet
        </p>
      ) : (
        <div className="space-y-4">
          {entries.map(([chain, health]) => {
            const overallStatus =
              health.providersHealthy === health.providersTotal
                ? "healthy" as const
                : health.providersHealthy > 0
                  ? "degraded" as const
                  : "down" as const;

            return (
              <div key={chain} className="border-b border-border/50 pb-3 last:border-0">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-sm font-medium text-text-primary">
                    {getChainDisplayName(chain)}
                  </span>
                  <StatusBadge status={overallStatus} size="sm" />
                </div>

                {/* Provider count summary */}
                <div className="flex items-center gap-2 mb-2 text-[11px] text-text-muted">
                  <span>{health.providersHealthy} healthy</span>
                  <span>/</span>
                  <span>{health.providersTotal} total</span>
                </div>

                {/* Individual providers */}
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
                          {p.failureCount > 0 && (
                            <span className="flex items-center gap-1 text-text-muted">
                              <Clock className="w-3 h-3" />
                              {p.failureCount} errors
                            </span>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>

                {/* Last update timestamp */}
                <div className="flex items-center gap-1 mt-2 text-[10px] text-text-muted">
                  <Clock className="w-3 h-3" />
                  Updated{" "}
                  {formatDistanceToNow(new Date(health.timestamp), {
                    addSuffix: true,
                  })}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </Card>
  );
}
