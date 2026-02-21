import { useMemo } from "react";
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
import type { HourlyGasPattern } from "@/types";
import { getChainColor, getChainDisplayName } from "@/utils/format";

interface HourlyGasPatternChartProps {
  data: HourlyGasPattern[];
  chainKeys: string[];
}

function formatHour(hour: number): string {
  return `${hour.toString().padStart(2, "0")}:00`;
}

export function HourlyGasPatternChart({
  data,
  chainKeys,
}: HourlyGasPatternChartProps) {
  const chartData = useMemo(() => {
    const byHour = new Map<number, Record<string, number | string | null>>();
    for (let h = 0; h < 24; h++) {
      byHour.set(h, { hour: formatHour(h) });
    }

    for (const d of data) {
      const row = byHour.get(d.hour);
      if (row) {
        row[d.chain] = d.avgBaseFee;
      }
    }

    return Array.from(byHour.values());
  }, [data]);

  return (
    <Card>
      <CardHeader
        title="Hourly Gas Patterns"
        subtitle="Avg base fee (Gwei) by hour of day (UTC)"
      />
      <div className="h-64">
        {data.length === 0 ? (
          <div className="flex items-center justify-center h-full text-xs text-text-muted">
            No hourly pattern data available
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
                dataKey="hour"
                tick={{ fontSize: 9, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
                interval={2}
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
                formatter={(value: number | undefined) =>
                  value != null ? `${value.toFixed(2)} Gwei` : "N/A"
                }
              />
              <Legend
                wrapperStyle={{ fontSize: 11 }}
                iconType="circle"
                iconSize={8}
              />
              {chainKeys.map((chain) => (
                <Bar
                  key={chain}
                  dataKey={chain}
                  fill={getChainColor(chain)}
                  opacity={0.8}
                  radius={[3, 3, 0, 0]}
                  name={getChainDisplayName(chain)}
                />
              ))}
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
    </Card>
  );
}
