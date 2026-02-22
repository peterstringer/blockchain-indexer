import { useMemo } from "react";
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
} from "recharts";
import type { DailyTransactionTypes } from "@/types";
import { getChainColor, getChainDisplayName } from "@/utils/format";

interface TxTypeEvolutionChartProps {
  data: DailyTransactionTypes[];
}

const TX_COLORS = {
  eip1559: "var(--color-accent-blue)",
  legacy: "var(--color-accent-amber)",
  contract: "var(--color-accent-green)",
} as const;

interface PercentRow {
  date: string;
  eip1559: number;
  legacy: number;
  contract: number;
}

function toPercent(data: DailyTransactionTypes[]): PercentRow[] {
  return data.map((d) => {
    const total = d.totalTxs || 1; // avoid /0
    return {
      date: d.date,
      eip1559: (d.totalEip1559 / total) * 100,
      legacy: (d.totalLegacy / total) * 100,
      contract: (d.totalContract / total) * 100,
    };
  });
}

export function TxTypeEvolutionChart({ data }: TxTypeEvolutionChartProps) {
  const byChain = useMemo(() => {
    const map = new Map<string, DailyTransactionTypes[]>();
    for (const d of data) {
      const list = map.get(d.chain);
      if (list) {
        list.push(d);
      } else {
        map.set(d.chain, [d]);
      }
    }
    return map;
  }, [data]);

  const chains = useMemo(() => [...byChain.keys()].sort(), [byChain]);

  if (chains.length === 0) {
    return (
      <div className="flex items-center justify-center h-72 text-xs text-text-muted">
        No transaction type data for the selected range
      </div>
    );
  }

  return (
    <div>
      {chains.map((chain, i) => {
        const chartData = toPercent(byChain.get(chain) ?? []);
        return (
          <div key={chain}>
            {chains.length > 1 && (
              <div className="flex items-center gap-1.5 mb-1.5 mt-1">
                <span
                  className="w-2 h-2 rounded-full shrink-0"
                  style={{ backgroundColor: getChainColor(chain) }}
                />
                <span className="text-xs font-medium text-text-secondary">
                  {getChainDisplayName(chain)}
                </span>
              </div>
            )}
            <div className={chains.length > 1 ? "h-48" : "h-72"}>
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={chartData}>
                  <CartesianGrid
                    strokeDasharray="3 3"
                    stroke="var(--color-border)"
                    vertical={false}
                  />
                  <XAxis
                    dataKey="date"
                    tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                    axisLine={false}
                    tickLine={false}
                    interval="preserveStartEnd"
                    hide={chains.length > 1 && i < chains.length - 1}
                  />
                  <YAxis
                    tickFormatter={(v: number) => `${Math.round(v)}%`}
                    tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                    axisLine={false}
                    tickLine={false}
                    width={45}
                    domain={[0, 100]}
                  />
                  <Tooltip content={<TxTypeTooltip />} />
                  <Area
                    type="monotone"
                    dataKey="eip1559"
                    stackId="1"
                    stroke={TX_COLORS.eip1559}
                    fill={TX_COLORS.eip1559}
                    fillOpacity={0.6}
                    strokeWidth={0}
                    isAnimationActive={false}
                  />
                  <Area
                    type="monotone"
                    dataKey="legacy"
                    stackId="1"
                    stroke={TX_COLORS.legacy}
                    fill={TX_COLORS.legacy}
                    fillOpacity={0.6}
                    strokeWidth={0}
                    isAnimationActive={false}
                  />
                  <Area
                    type="monotone"
                    dataKey="contract"
                    stackId="1"
                    stroke={TX_COLORS.contract}
                    fill={TX_COLORS.contract}
                    fillOpacity={0.6}
                    strokeWidth={0}
                    isAnimationActive={false}
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </div>
        );
      })}

      {/* Legend */}
      <div className="flex flex-wrap items-center gap-x-5 gap-y-1 mt-3 px-1">
        <LegendItem color={TX_COLORS.eip1559} label="EIP-1559" />
        <LegendItem color={TX_COLORS.legacy} label="Legacy" />
        <LegendItem color={TX_COLORS.contract} label="Contract Creation" />
      </div>
    </div>
  );
}

function LegendItem({ color, label }: { color: string; label: string }) {
  return (
    <div className="flex items-center gap-1.5">
      <span
        className="inline-block w-3 h-2.5 rounded-sm"
        style={{ backgroundColor: color, opacity: 0.7 }}
      />
      <span className="text-[10px] text-text-muted">{label}</span>
    </div>
  );
}

function TxTypeTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean;
  payload?: Array<{ dataKey: string; value: number }>;
  label?: string;
}) {
  if (!active || !payload?.length) return null;

  const items: { key: string; label: string; color: string; value: number }[] = [];
  for (const entry of payload) {
    const key = entry.dataKey as keyof typeof TX_COLORS;
    if (key in TX_COLORS) {
      items.push({
        key,
        label:
          key === "eip1559"
            ? "EIP-1559"
            : key === "legacy"
              ? "Legacy"
              : "Contract",
        color: TX_COLORS[key],
        value: entry.value,
      });
    }
  }

  return (
    <div className="rounded-lg bg-bg-secondary border border-border px-3 py-2 shadow-lg text-xs">
      <div className="text-text-muted mb-1.5 font-medium">{label}</div>
      {items.map((item) => (
        <div key={item.key} className="flex items-center justify-between gap-4 mb-0.5 last:mb-0">
          <div className="flex items-center gap-1.5">
            <span
              className="w-2 h-2 rounded-full shrink-0"
              style={{ backgroundColor: item.color }}
            />
            <span className="text-text-secondary">{item.label}</span>
          </div>
          <span className="font-mono text-text-primary">{item.value.toFixed(1)}%</span>
        </div>
      ))}
    </div>
  );
}
