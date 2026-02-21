import { useMemo } from "react";
import {
  PieChart,
  Pie,
  Cell,
  Tooltip,
  ResponsiveContainer,
  Legend,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
} from "recharts";
import { Card, CardHeader } from "@/components/common/Card";
import type { TransactionTypeAnalysis } from "@/types";
import { getChainDisplayName } from "@/utils/format";

interface TransactionTypeChartProps {
  data: TransactionTypeAnalysis[];
  selectedChain?: string;
}

const TYPE_COLORS = {
  legacy: "#f59e0b",
  eip1559: "#3b82f6",
  contract: "#10b981",
  failed: "#ef4444",
};

export function TransactionTypeChart({
  data,
  selectedChain,
}: TransactionTypeChartProps) {
  // Aggregate across chains if no chain selected
  const aggregated = useMemo(() => {
    if (data.length === 0) return null;

    const target = selectedChain
      ? data.find((d) => d.chain === selectedChain)
      : data.reduce(
          (acc, d) => ({
            chain: "all",
            totalLegacy: acc.totalLegacy + d.totalLegacy,
            totalEip1559: acc.totalEip1559 + d.totalEip1559,
            totalContract: acc.totalContract + d.totalContract,
            totalFailed: acc.totalFailed + d.totalFailed,
            avgGasLegacy: d.avgGasLegacy,
            avgGasEip1559: d.avgGasEip1559,
            avgGasContract: d.avgGasContract,
          }),
          {
            chain: "all",
            totalLegacy: 0,
            totalEip1559: 0,
            totalContract: 0,
            totalFailed: 0,
            avgGasLegacy: null as number | null,
            avgGasEip1559: null as number | null,
            avgGasContract: null as number | null,
          }
        );

    if (!target) return null;
    return target;
  }, [data, selectedChain]);

  const pieData = useMemo(() => {
    if (!aggregated) return [];
    return [
      { name: "Legacy", value: aggregated.totalLegacy, color: TYPE_COLORS.legacy },
      { name: "EIP-1559", value: aggregated.totalEip1559, color: TYPE_COLORS.eip1559 },
      { name: "Contract", value: aggregated.totalContract, color: TYPE_COLORS.contract },
    ].filter((d) => d.value > 0);
  }, [aggregated]);

  const gasData = useMemo(() => {
    if (!aggregated) return [];
    const items: { type: string; avgGas: number; color: string }[] = [];
    if (aggregated.avgGasLegacy != null)
      items.push({ type: "Legacy", avgGas: aggregated.avgGasLegacy, color: TYPE_COLORS.legacy });
    if (aggregated.avgGasEip1559 != null)
      items.push({ type: "EIP-1559", avgGas: aggregated.avgGasEip1559, color: TYPE_COLORS.eip1559 });
    if (aggregated.avgGasContract != null)
      items.push({ type: "Contract", avgGas: aggregated.avgGasContract, color: TYPE_COLORS.contract });
    return items;
  }, [aggregated]);

  const title = selectedChain
    ? `${getChainDisplayName(selectedChain)} Transaction Types`
    : "Transaction Types";

  const totalTxs = aggregated
    ? aggregated.totalLegacy + aggregated.totalEip1559 + aggregated.totalContract
    : 0;

  return (
    <Card>
      <CardHeader
        title={title}
        subtitle={totalTxs > 0 ? `${totalTxs.toLocaleString()} total transactions` : "Distribution by type"}
      />
      <div className="h-64">
        {pieData.length === 0 ? (
          <div className="flex items-center justify-center h-full text-xs text-text-muted">
            No transaction data available
          </div>
        ) : (
          <div className="flex h-full">
            {/* Pie chart — type distribution */}
            <div className="flex-1">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={pieData}
                    cx="50%"
                    cy="50%"
                    innerRadius="45%"
                    outerRadius="75%"
                    dataKey="value"
                    nameKey="name"
                    paddingAngle={2}
                  >
                    {pieData.map((entry) => (
                      <Cell key={entry.name} fill={entry.color} />
                    ))}
                  </Pie>
                  <Tooltip
                    contentStyle={{
                      backgroundColor: "var(--color-bg-secondary)",
                      border: "1px solid var(--color-border)",
                      borderRadius: 8,
                      fontSize: 12,
                    }}
                    formatter={(value: number | undefined) => [
                      value != null
                        ? `${value.toLocaleString()} (${((value / totalTxs) * 100).toFixed(1)}%)`
                        : "N/A",
                      "Count",
                    ]}
                  />
                  <Legend
                    wrapperStyle={{ fontSize: 11 }}
                    iconType="circle"
                    iconSize={8}
                  />
                </PieChart>
              </ResponsiveContainer>
            </div>

            {/* Bar chart — avg gas per type */}
            {gasData.length > 0 && (
              <div className="flex-1">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={gasData} layout="vertical">
                    <CartesianGrid
                      strokeDasharray="3 3"
                      stroke="var(--color-border)"
                      horizontal={false}
                    />
                    <XAxis
                      type="number"
                      tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                      axisLine={false}
                      tickLine={false}
                      tickFormatter={(v: number) =>
                        v >= 1_000_000 ? `${(v / 1_000_000).toFixed(0)}M` : `${(v / 1000).toFixed(0)}K`
                      }
                    />
                    <YAxis
                      type="category"
                      dataKey="type"
                      tick={{ fontSize: 11, fill: "var(--color-text-muted)" }}
                      axisLine={false}
                      tickLine={false}
                      width={65}
                    />
                    <Tooltip
                      contentStyle={{
                        backgroundColor: "var(--color-bg-secondary)",
                        border: "1px solid var(--color-border)",
                        borderRadius: 8,
                        fontSize: 12,
                      }}
                      formatter={(value: number | undefined) => [
                        value != null ? value.toLocaleString() : "N/A",
                        "Avg Gas",
                      ]}
                    />
                    <Bar dataKey="avgGas" radius={[0, 4, 4, 0]} name="Avg Gas Used">
                      {gasData.map((entry) => (
                        <Cell key={entry.type} fill={entry.color} opacity={0.8} />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </div>
        )}
      </div>
      {/* Failed tx count */}
      {aggregated && aggregated.totalFailed > 0 && (
        <div className="px-4 pb-3 pt-1 text-[10px] text-accent-red">
          {aggregated.totalFailed.toLocaleString()} failed transactions (
          {((aggregated.totalFailed / totalTxs) * 100).toFixed(1)}%)
        </div>
      )}
    </Card>
  );
}
