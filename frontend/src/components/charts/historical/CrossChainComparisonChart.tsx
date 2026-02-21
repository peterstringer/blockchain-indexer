import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
  Legend,
} from "recharts";
import { Card, CardHeader } from "@/components/common/Card";
import type { CrossChainComparison } from "@/types";
import { getChainDisplayName } from "@/utils/format";

interface CrossChainComparisonChartProps {
  data: CrossChainComparison[];
}

export function CrossChainComparisonChart({
  data,
}: CrossChainComparisonChartProps) {
  const chartData = data.map((d) => ({
    chain: getChainDisplayName(d.chain),
    avgTxCount: d.avgTxCount,
    avgBaseFee: d.avgBaseFee,
    totalTxs: d.totalTxs,
    blockCount: d.blockCount,
  }));

  return (
    <Card>
      <CardHeader
        title="Cross-Chain Comparison"
        subtitle="Avg transactions & gas price by chain"
      />
      <div className="h-64">
        {chartData.length === 0 ? (
          <div className="flex items-center justify-center h-full text-xs text-text-muted">
            No comparison data available
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
                yAxisId="left"
                tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
                width={45}
                label={{
                  value: "Avg Txs",
                  position: "insideLeft",
                  offset: 10,
                  style: { fontSize: 10, fill: "var(--color-text-muted)" },
                }}
              />
              <YAxis
                yAxisId="right"
                orientation="right"
                tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
                width={50}
                label={{
                  value: "Gwei",
                  position: "insideRight",
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
                formatter={(value: number | undefined, name: string | undefined) => {
                  if (value == null) return ["N/A", name ?? ""];
                  if (name === "Avg Tx Count") return [value.toFixed(1), name];
                  if (name === "Avg Base Fee")
                    return [`${value.toFixed(2)} Gwei`, name];
                  return [`${value}`, name ?? ""];
                }}
              />
              <Legend
                wrapperStyle={{ fontSize: 11 }}
                iconType="circle"
                iconSize={8}
              />
              <Bar
                yAxisId="left"
                dataKey="avgTxCount"
                fill="var(--color-accent-blue)"
                opacity={0.8}
                radius={[4, 4, 0, 0]}
                name="Avg Tx Count"
              />
              <Bar
                yAxisId="right"
                dataKey="avgBaseFee"
                fill="var(--color-accent-purple)"
                opacity={0.8}
                radius={[4, 4, 0, 0]}
                name="Avg Base Fee"
              />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
      {/* Summary row */}
      {chartData.length > 0 && (
        <div className="flex gap-4 px-4 pb-3 pt-1">
          {chartData.map((d) => (
            <div key={d.chain} className="text-[10px] text-text-muted">
              <span className="font-medium text-text-secondary">{d.chain}:</span>{" "}
              {d.totalTxs.toLocaleString()} total txs across{" "}
              {d.blockCount.toLocaleString()} blocks
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}
