import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
} from "recharts";
import { Card, CardHeader } from "@/components/common/Card";
import type { BlockIndexedMessage } from "@/types";
import { getChainColor, formatBlock } from "@/utils/format";

interface GasChartProps {
  chain: string;
  blocks: BlockIndexedMessage[];
}

export function GasChart({ chain, blocks }: GasChartProps) {
  const data = blocks
    .slice(0, 20)
    .reverse()
    .map((b) => ({
      block: formatBlock(b.blockNumber),
      gasUsed: b.gasUsed,
      baseFee: b.baseFeeGwei ?? 0,
    }));

  const color = getChainColor(chain);

  return (
    <Card>
      <CardHeader
        title="Gas Usage"
        subtitle="Gas consumed per recent block"
      />
      <div className="h-48">
        {data.length === 0 ? (
          <div className="flex items-center justify-center h-full text-xs text-text-muted">
            No block data yet
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data}>
              <CartesianGrid
                strokeDasharray="3 3"
                stroke="var(--color-border)"
                vertical={false}
              />
              <XAxis
                dataKey="block"
                tick={{ fontSize: 9, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
                interval="preserveStartEnd"
              />
              <YAxis
                tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
                width={50}
                tickFormatter={(v: number) =>
                  v >= 1_000_000 ? `${(v / 1_000_000).toFixed(0)}M` : `${v}`
                }
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
              <Bar
                dataKey="gasUsed"
                fill={color}
                opacity={0.7}
                radius={[4, 4, 0, 0]}
                name="Gas Used"
              />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
    </Card>
  );
}
