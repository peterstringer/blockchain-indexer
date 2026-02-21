import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
  Cell,
  ReferenceLine,
} from "recharts";
import { Card, CardHeader } from "@/components/common/Card";
import type { BlockFullness } from "@/types";
import { getChainColor, getChainDisplayName } from "@/utils/format";

interface BlockFullnessChartProps {
  data: BlockFullness[];
}

export function BlockFullnessChart({ data }: BlockFullnessChartProps) {
  const chartData = data.map((d) => ({
    chain: getChainDisplayName(d.chain),
    chainKey: d.chain,
    avgFullness: d.avgFullness,
    minFullness: d.minFullness,
    maxFullness: d.maxFullness,
    blockCount: d.blockCount,
  }));

  return (
    <Card>
      <CardHeader
        title="Block Fullness"
        subtitle="Avg gas utilization % by chain"
      />
      <div className="h-64">
        {chartData.length === 0 ? (
          <div className="flex items-center justify-center h-full text-xs text-text-muted">
            No block fullness data available
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={chartData}>
              <CartesianGrid
                strokeDasharray="3 3"
                stroke="var(--color-border)"
                vertical={false}
              />
              <XAxis
                dataKey="chain"
                tick={{ fontSize: 11, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
              />
              <YAxis
                domain={[0, 100]}
                tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
                width={40}
                tickFormatter={(v: number) => `${v}%`}
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: "var(--color-bg-secondary)",
                  border: "1px solid var(--color-border)",
                  borderRadius: 8,
                  fontSize: 12,
                }}
                labelStyle={{ color: "var(--color-text-muted)" }}
                formatter={(value: number | undefined, name: string | undefined) => {
                  if (value == null) return ["N/A", name ?? ""];
                  if (name === "avgFullness") return [`${value.toFixed(1)}%`, "Avg Fullness"];
                  return [`${value}`, name ?? ""];
                }}
              />
              <ReferenceLine
                y={50}
                stroke="var(--color-text-muted)"
                strokeDasharray="3 3"
                strokeOpacity={0.3}
              />
              <Bar
                dataKey="avgFullness"
                radius={[6, 6, 0, 0]}
                name="Avg Fullness"
              >
                {chartData.map((entry) => (
                  <Cell
                    key={entry.chainKey}
                    fill={getChainColor(entry.chainKey)}
                    opacity={0.85}
                  />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
      {/* Summary stats below chart */}
      {chartData.length > 0 && (
        <div className="flex gap-4 px-4 pb-3 pt-1">
          {chartData.map((d) => (
            <div key={d.chainKey} className="text-[10px] text-text-muted">
              <span className="font-medium text-text-secondary">{d.chain}:</span>{" "}
              {d.minFullness.toFixed(0)}% – {d.maxFullness.toFixed(0)}% range,{" "}
              {d.blockCount.toLocaleString()} blocks
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}
