import { useMemo } from "react";
import {
  ComposedChart,
  Area,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
  Legend,
} from "recharts";
import { Card, CardHeader } from "@/components/common/Card";
import type { DailyGasPrice } from "@/types";
import { getChainColor, getChainDisplayName } from "@/utils/format";

interface DailyGasPriceChartProps {
  data: DailyGasPrice[];
  chainKeys: string[];
}

export function DailyGasPriceChart({ data, chainKeys }: DailyGasPriceChartProps) {
  const chartData = useMemo(() => {
    const byDate = new Map<string, Record<string, number | string | null>>();

    for (const d of data) {
      if (!byDate.has(d.date)) {
        byDate.set(d.date, { date: d.date });
      }
      const row = byDate.get(d.date)!;
      row[`${d.chain}_avg`] = d.avgBaseFee;
      row[`${d.chain}_min`] = d.minBaseFee;
      row[`${d.chain}_max`] = d.maxBaseFee;
    }

    return Array.from(byDate.values()).sort((a, b) =>
      (a.date as string).localeCompare(b.date as string)
    );
  }, [data]);

  return (
    <Card>
      <CardHeader
        title="Daily Gas Prices"
        subtitle="Avg/min/max base fee (Gwei) per day"
      />
      <div className="h-72">
        {chartData.length === 0 ? (
          <div className="flex items-center justify-center h-full text-xs text-text-muted">
            No gas price data available
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <ComposedChart data={chartData}>
              <defs>
                {chainKeys.map((chain) => (
                  <linearGradient
                    key={chain}
                    id={`band-${chain}`}
                    x1="0"
                    y1="0"
                    x2="0"
                    y2="1"
                  >
                    <stop
                      offset="0%"
                      stopColor={getChainColor(chain)}
                      stopOpacity={0.15}
                    />
                    <stop
                      offset="100%"
                      stopColor={getChainColor(chain)}
                      stopOpacity={0.05}
                    />
                  </linearGradient>
                ))}
              </defs>
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
              />
              <YAxis
                tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
                width={50}
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
              {chainKeys.map((chain) => (
                <Area
                  key={`${chain}-band`}
                  type="monotone"
                  dataKey={`${chain}_max`}
                  stroke="none"
                  fill={`url(#band-${chain})`}
                  name={`${getChainDisplayName(chain)} Max`}
                  legendType="none"
                  connectNulls
                />
              ))}
              {chainKeys.map((chain) => (
                <Line
                  key={`${chain}-avg`}
                  type="monotone"
                  dataKey={`${chain}_avg`}
                  stroke={getChainColor(chain)}
                  strokeWidth={2}
                  dot={false}
                  name={`${getChainDisplayName(chain)} Avg`}
                  connectNulls
                />
              ))}
              {chainKeys.map((chain) => (
                <Line
                  key={`${chain}-min`}
                  type="monotone"
                  dataKey={`${chain}_min`}
                  stroke={getChainColor(chain)}
                  strokeWidth={1}
                  strokeDasharray="4 4"
                  dot={false}
                  name={`${getChainDisplayName(chain)} Min`}
                  legendType="none"
                  connectNulls
                />
              ))}
            </ComposedChart>
          </ResponsiveContainer>
        )}
      </div>
    </Card>
  );
}
