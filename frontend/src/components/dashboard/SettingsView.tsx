import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Info, Database, Server, Save } from "lucide-react";
import { Card, CardHeader } from "@/components/common/Card";
import { MetricValue } from "@/components/common/MetricValue";
import { StatusBadge } from "@/components/common/StatusBadge";
import { useToast } from "@/components/common/Toast";
import { fetchHealth, fetchCheckpoints, fetchConfig, updateStartBlock } from "@/services/api";
import type { IndexerStatus } from "@/types";
import { isRpcHealthObject } from "@/types";
import { formatBlock, formatNumber, getChainDisplayName } from "@/utils/format";

interface SettingsViewProps {
  status: IndexerStatus;
}

export function SettingsView({ status }: SettingsViewProps) {
  const { toast } = useToast();
  const queryClient = useQueryClient();

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

  const { data: chainConfigs } = useQuery({
    queryKey: ["chain-config"],
    queryFn: fetchConfig,
    refetchInterval: 30000,
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
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          <MetricValue
            label="Mode"
            value={status.mode}
            icon={<Server className="w-3 h-3" />}
          />
          <MetricValue
            label="Status"
            value={status.running ? "Running" : "Stopped"}
            icon={<Server className="w-3 h-3" />}
          />
          <MetricValue
            label="Demo Mode"
            value={health?.demoMode ? "Enabled" : "Disabled"}
            icon={<Database className="w-3 h-3" />}
          />
        </div>
      </Card>

      {/* Chain configuration with editable start block */}
      <Card>
        <CardHeader
          title="Chain Configuration"
          subtitle="Configured blockchain networks — edit start block to change backfill target"
        />
        <div className="space-y-3">
          {Object.entries(status.chains).map(([key, chain]) => {
            const rpcObj = isRpcHealthObject(chain.rpcHealth) ? chain.rpcHealth : null;
            const totalProviders = rpcObj?.totalProviders ?? 0;
            const healthyProviders = rpcObj?.healthyProviders ?? 0;
            const config = chainConfigs?.find((c) => c.chain === key);
            const isActive =
              typeof chain.rpcHealth === "string" &&
              chain.rpcHealth !== "STOPPED" &&
              chain.rpcHealth !== "NOT_STARTED";

            return (
              <ChainConfigRow
                key={key}
                chainKey={key}
                chainId={chain.chainId}
                totalProviders={totalProviders}
                healthyProviders={healthyProviders}
                rpcObj={rpcObj}
                rpcHealth={chain.rpcHealth}
                currentStartBlock={config?.startBlock ?? null}
                isActive={isActive}
                onSaved={() => {
                  queryClient.invalidateQueries({ queryKey: ["chain-config"] });
                  queryClient.invalidateQueries({ queryKey: ["indexer-status"] });
                  toast("success", `Start block updated for ${getChainDisplayName(key)}`);
                }}
                onError={(msg) => toast("error", msg)}
              />
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

function ChainConfigRow({
  chainKey,
  chainId,
  totalProviders,
  healthyProviders,
  rpcObj,
  rpcHealth,
  currentStartBlock,
  isActive,
  onSaved,
  onError,
}: {
  chainKey: string;
  chainId?: number;
  totalProviders: number;
  healthyProviders: number;
  rpcObj: { totalProviders: number; healthyProviders: number } | null;
  rpcHealth: unknown;
  currentStartBlock: number | null;
  isActive: boolean;
  onSaved: () => void;
  onError: (msg: string) => void;
}) {
  const [editValue, setEditValue] = useState<string>("");
  const [editing, setEditing] = useState(false);

  const mutation = useMutation({
    mutationFn: (newBlock: number) => updateStartBlock(chainKey, newBlock),
    onSuccess: () => {
      setEditing(false);
      setEditValue("");
      onSaved();
    },
    onError: (err: Error) => {
      onError(`Failed to update: ${err.message}`);
    },
  });

  const handleSave = () => {
    const parsed = parseInt(editValue, 10);
    if (isNaN(parsed) || parsed < 0) {
      onError("Start block must be a non-negative number");
      return;
    }
    mutation.mutate(parsed);
  };

  const handleStartEdit = () => {
    setEditValue(currentStartBlock != null ? String(currentStartBlock) : "0");
    setEditing(true);
  };

  return (
    <div className="bg-bg-primary/50 rounded-lg px-4 py-3">
      <div className="flex items-center justify-between">
        <div>
          <span className="text-sm font-medium text-text-primary">
            {getChainDisplayName(chainKey)}
          </span>
          {chainId != null && (
            <span className="text-xs text-text-muted ml-2">
              Chain ID: {chainId}
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
              {String(rpcHealth)}
            </span>
          )}
        </div>
      </div>

      {/* Start block config row */}
      <div className="flex items-center gap-3 mt-2 pt-2 border-t border-border/30">
        <span className="text-xs text-text-muted shrink-0">Start Block:</span>
        {editing ? (
          <div className="flex items-center gap-2 flex-1">
            <input
              type="number"
              min={0}
              value={editValue}
              onChange={(e) => setEditValue(e.target.value)}
              className="w-40 px-2 py-1 text-xs font-mono bg-bg-primary border border-border rounded-md text-text-primary focus:border-accent-blue focus:outline-none"
              onKeyDown={(e) => {
                if (e.key === "Enter") handleSave();
                if (e.key === "Escape") { setEditing(false); setEditValue(""); }
              }}
              autoFocus
            />
            <button
              onClick={handleSave}
              disabled={mutation.isPending}
              className="flex items-center gap-1 px-2 py-1 text-[11px] font-medium rounded-md bg-accent-green/10 text-accent-green border border-accent-green/20 hover:bg-accent-green/20 transition-colors disabled:opacity-50"
            >
              <Save className="w-3 h-3" />
              Save
            </button>
            <button
              onClick={() => { setEditing(false); setEditValue(""); }}
              className="px-2 py-1 text-[11px] font-medium rounded-md text-text-muted hover:text-text-secondary transition-colors"
            >
              Cancel
            </button>
          </div>
        ) : (
          <div className="flex items-center gap-2 flex-1">
            <span className="text-xs font-mono text-text-secondary">
              {currentStartBlock != null ? formatNumber(currentStartBlock) : "—"}
            </span>
            <button
              onClick={handleStartEdit}
              disabled={isActive}
              title={isActive ? "Stop indexing before changing start block" : "Edit start block"}
              className="px-2 py-0.5 text-[11px] font-medium rounded-md text-accent-blue hover:bg-accent-blue/10 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              Edit
            </button>
            {isActive && (
              <span className="text-[10px] text-text-muted italic">
                Stop indexing to edit
              </span>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
