import { useState, useMemo } from "react";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
  Legend,
} from "recharts";
import { Card, CardHeader } from "@/components/common/Card";
import type { BlockIndexedMessage } from "@/types";
import { getChainColor, getChainDisplayName } from "@/utils/format";

type TimeRange = "1h" | "6h" | "24h" | "all";

interface GasPriceChartProps {
  blocksByChain: Record<string, BlockIndexedMessage[]>;
  chainKeys: string[];
}

const RANGE_LABELS: { id: TimeRange; label: string }[] = [
  { id: "1h", label: "1H" },
  { id: "6h", label: "6H" },
  { id: "24h", label: "24H" },
  { id: "all", label: "All" },
];

const RANGE_MS: Record<TimeRange, number> = {
  "1h": 3_600_000,
  "6h": 21_600_000,
  "24h": 86_400_000,
  all: Infinity,
};

export function GasPriceChart({ blocksByChain, chainKeys }: GasPriceChartProps) {
  const [range, setRange] = useState<TimeRange>("all");
  const [selectedChain, setSelectedChain] = useState<string | "all">("all");

  const data = useMemo(() => {
    const now = Date.now();
    const cutoff = now - RANGE_MS[range];

    // Merge all blocks from visible chains into a timeline
    const visibleChains =
      selectedChain === "all" ? chainKeys : [selectedChain];

    // Collect all blocks, bin by block number or time
    const allBlocks: { time: number; chain: string; baseFee: number }[] = [];

    for (const chain of visibleChains) {
      const blocks = blocksByChain[chain] ?? [];
      for (const b of blocks) {
        const t = new Date(b.timestamp).getTime();
        if (t >= cutoff && b.baseFeeGwei != null) {
          allBlocks.push({ time: t, chain, baseFee: b.baseFeeGwei });
        }
      }
    }

    allBlocks.sort((a, b) => a.time - b.time);

    // Build chart data points
    return allBlocks.map((b) => ({
      time: new Date(b.time).toLocaleTimeString(),
      [b.chain]: b.baseFee,
    }));
  }, [blocksByChain, chainKeys, range, selectedChain]);

  const visibleChains =
    selectedChain === "all" ? chainKeys : [selectedChain];

  return (
    <Card>
      <CardHeader
        title="Gas Prices"
        subtitle="Base fee (Gwei) over time"
        action={
          <div className="flex items-center gap-2">
            {/* Chain filter */}
            <select
              value={selectedChain}
              onChange={(e) => setSelectedChain(e.target.value)}
              className="text-[11px] bg-bg-primary border border-border rounded-md px-2 py-1 text-text-secondary"
            >
              <option value="all">All Chains</option>
              {chainKeys.map((c) => (
                <option key={c} value={c}>
                  {getChainDisplayName(c)}
                </option>
              ))}
            </select>

            {/* Time range */}
            <div className="flex rounded-md border border-border overflow-hidden">
              {RANGE_LABELS.map(({ id, label }) => (
                <button
                  key={id}
                  onClick={() => setRange(id)}
                  className={`px-2 py-1 text-[11px] font-medium transition-colors ${
                    range === id
                      ? "bg-accent-purple/20 text-accent-purple"
                      : "text-text-muted hover:text-text-secondary"
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>
        }
      />
      <div className="h-64">
        {data.length < 2 ? (
          <div className="flex items-center justify-center h-full text-xs text-text-muted">
            Collecting gas price data...
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data}>
              <CartesianGrid
                strokeDasharray="3 3"
                stroke="var(--color-border)"
                vertical={false}
              />
              <XAxis
                dataKey="time"
                tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
              />
              <YAxis
                tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
                width={45}
                label={{
                  value: "Gwei",
                  position: "insideLeft",
                  offset: 10,
                  style: { fontSize: 10, fill: "var(--color-text-muted)" },
                }}
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: "var(--color-bg-secondary)",
                  border: "1px solid var(--color-border)",
                  borderRadius: 8,
                  fontSize: 12,
                }}
                labelStyle={{ color: "var(--color-text-muted)" }}
              />
              <Legend
                wrapperStyle={{ fontSize: 11 }}
                iconType="circle"
                iconSize={8}
              />
              {visibleChains.map((chain) => (
                <Line
                  key={chain}
                  type="monotone"
                  dataKey={chain}
                  stroke={getChainColor(chain)}
                  strokeWidth={2}
                  dot={false}
                  name={getChainDisplayName(chain)}
                  connectNulls
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </Card>
  );
}
