import { useQuery } from "@tanstack/react-query";
import { Info, Database, Server, Clock } from "lucide-react";
import { Card, CardHeader } from "@/components/common/Card";
import { MetricValue } from "@/components/common/MetricValue";
import { StatusBadge } from "@/components/common/StatusBadge";
import { fetchHealth, fetchCheckpoints } from "@/services/api";
import type { IndexerStatus } from "@/types";
import { isRpcHealthObject } from "@/types";
import { formatBlock, getChainDisplayName } from "@/utils/format";

interface SettingsViewProps {
  status: IndexerStatus;
}

export function SettingsView({ status }: SettingsViewProps) {
  const { data: health } = useQuery({
    queryKey: ["health"],
    queryFn: fetchHealth,
    refetchInterval: 10000,
  });

  const { data: checkpoints } = useQuery({
    queryKey: ["checkpoints"],
    queryFn: fetchCheckpoints,
    refetchInterval: 10000,
  });

  return (
    <div className="space-y-6">
      {/* System info */}
      <Card>
        <CardHeader
          title="System Information"
          subtitle="Current indexer configuration and status"
          action={<Info className="w-4 h-4 text-text-muted" />}
        />
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <MetricValue
            label="Mode"
            value={status.mode}
            icon={<Server className="w-3 h-3" />}
          />
          <MetricValue
            label="Uptime"
            value={status.uptime ?? "N/A"}
            icon={<Clock className="w-3 h-3" />}
          />
          <MetricValue
            label="Started At"
            value={
              status.startedAt
                ? new Date(status.startedAt).toLocaleString()
                : "Not started"
            }
            icon={<Clock className="w-3 h-3" />}
          />
          <MetricValue
            label="Demo Mode"
            value={health?.demoMode ? "Enabled" : "Disabled"}
            icon={<Database className="w-3 h-3" />}
          />
        </div>
      </Card>

      {/* Chain configuration */}
      <Card>
        <CardHeader
          title="Chain Configuration"
          subtitle="Configured blockchain networks"
        />
        <div className="space-y-3">
          {Object.entries(status.chains).map(([key, chain]) => {
            const rpcObj = isRpcHealthObject(chain.rpcHealth) ? chain.rpcHealth : null;
            const totalProviders = rpcObj?.totalProviders ?? 0;
            const healthyProviders = rpcObj?.healthyProviders ?? 0;

            return (
              <div
                key={key}
                className="flex items-center justify-between bg-bg-primary/50 rounded-lg px-4 py-3"
              >
                <div>
                  <span className="text-sm font-medium text-text-primary">
                    {getChainDisplayName(key)}
                  </span>
                  {chain.chainId != null && (
                    <span className="text-xs text-text-muted ml-2">
                      Chain ID: {chain.chainId}
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-4">
                  {rpcObj ? (
                    <>
                      <span className="text-xs text-text-muted">
                        {totalProviders} RPC provider
                        {totalProviders !== 1 ? "s" : ""}
                      </span>
                      <StatusBadge
                        status={
                          healthyProviders === totalProviders
                            ? "healthy"
                            : healthyProviders > 0
                              ? "degraded"
                              : "down"
                        }
                        size="sm"
                      />
                    </>
                  ) : (
                    <span className="text-xs text-text-muted">
                      {String(chain.rpcHealth)}
                    </span>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </Card>

      {/* Checkpoints */}
      <Card>
        <CardHeader
          title="Checkpoints"
          subtitle="Last persisted block numbers for crash recovery"
          action={<Database className="w-4 h-4 text-text-muted" />}
        />
        {checkpoints ? (
          <div className="space-y-2">
            {checkpoints.map((cp) => (
              <div
                key={cp.chain}
                className="flex items-center justify-between bg-bg-primary/50 rounded-lg px-4 py-2.5"
              >
                <span className="text-sm text-text-secondary">
                  {getChainDisplayName(cp.chain)}
                </span>
                <span className="text-sm font-mono text-text-primary">
                  #{formatBlock(cp.lastIndexedBlock)}
                </span>
              </div>
            ))}
            {checkpoints.length === 0 && (
              <p className="text-xs text-text-muted text-center py-4">
                No checkpoints saved yet
              </p>
            )}
          </div>
        ) : (
          <p className="text-xs text-text-muted text-center py-4">
            Loading checkpoints...
          </p>
        )}
      </Card>

      {/* Backend health */}
      {health && (
        <Card>
          <CardHeader
            title="Backend Health"
            subtitle="Backend service status"
          />
          <div className="flex items-center gap-3">
            <StatusBadge
              status={health.status === "healthy" ? "healthy" : "down"}
              label={health.status}
            />
            <span className="text-xs text-text-muted">
              {health.chainsConfigured} chain
              {health.chainsConfigured !== 1 ? "s" : ""} configured
            </span>
          </div>
        </Card>
      )}
    </div>
  );
}
