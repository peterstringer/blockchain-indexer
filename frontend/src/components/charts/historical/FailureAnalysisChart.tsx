import { useMemo } from "react";
import {
  ComposedChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
} from "recharts";
import type { DailyFailureRate } from "@/types";
import { getChainColor, getChainDisplayName } from "@/utils/format";

interface FailureAnalysisChartProps {
  data: DailyFailureRate[];
}

type Row = Record<string, number | string | null>;

export function FailureAnalysisChart({ data }: FailureAnalysisChartProps) {
  const chains = useMemo(
    () => [...new Set(data.map((d) => d.chain))],
    [data]
  );

  const chartData = useMemo(() => {
    const byDate = new Map<string, Row>();

    for (const d of data) {
      if (!byDate.has(d.date)) {
        byDate.set(d.date, { date: d.date });
      }
      const row = byDate.get(d.date)!;
      row[`${d.chain}_failRate`] = d.failureRatePercent;
      row[`${d.chain}_gasPrice`] = d.avgGasPriceGwei;
    }

    return Array.from(byDate.values()).sort((a, b) =>
      (a.date as string).localeCompare(b.date as string)
    );
  }, [data]);

  if (chartData.length === 0) {
    return (
      <div className="flex items-center justify-center h-72 text-xs text-text-muted">
        No failure data for the selected range
      </div>
    );
  }

  return (
    <div>
      <div className="h-80">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={chartData}>
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
              yAxisId="failRate"
              tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
              axisLine={false}
              tickLine={false}
              width={45}
              tickFormatter={(v: number) => `${v.toFixed(1)}%`}
              label={{
                value: "Fail %",
                position: "insideLeft",
                offset: 10,
                style: { fontSize: 10, fill: "var(--color-text-muted)" },
              }}
            />
            <YAxis
              yAxisId="gasPrice"
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
            <Tooltip content={<FailureTooltip chains={chains} />} />

            {/* Failure rate lines (left axis, solid) */}
            {chains.map((chain) => (
              <Line
                key={`${chain}-failRate`}
                yAxisId="failRate"
                type="monotone"
                dataKey={`${chain}_failRate`}
                stroke={getChainColor(chain)}
                strokeWidth={2}
                dot={false}
                connectNulls
                isAnimationActive={false}
              />
            ))}

            {/* Gas price lines (right axis, dashed, lower opacity) */}
            {chains.map((chain) => (
              <Line
                key={`${chain}-gasPrice`}
                yAxisId="gasPrice"
                type="monotone"
                dataKey={`${chain}_gasPrice`}
                stroke={getChainColor(chain)}
                strokeWidth={1.5}
                strokeDasharray="6 3"
                strokeOpacity={0.5}
                dot={false}
                connectNulls
                isAnimationActive={false}
              />
            ))}
          </ComposedChart>
        </ResponsiveContainer>
      </div>

      {/* Legend */}
      <div className="flex flex-wrap items-center gap-x-5 gap-y-1 mt-3 px-1">
        {chains.map((chain) => (
          <div key={chain} className="flex items-center gap-2">
            <span
              className="inline-block w-5 h-0.5 rounded"
              style={{ backgroundColor: getChainColor(chain) }}
            />
            <span className="text-[10px] text-text-secondary">
              {getChainDisplayName(chain)}
            </span>
          </div>
        ))}
        <div className="flex items-center gap-4 ml-auto text-[10px] text-text-muted">
          <span className="flex items-center gap-1.5">
            <span className="inline-block w-4 h-0.5 bg-text-muted rounded" />
            Failure rate %
          </span>
          <span className="flex items-center gap-1.5">
            <span className="inline-block w-4 h-0 border-t border-dashed border-text-muted opacity-50" />
            Avg gas price (Gwei)
          </span>
        </div>
      </div>
    </div>
  );
}

/** Custom tooltip showing failure rate and gas price per chain */
function FailureTooltip({
  active,
  payload,
  label,
  chains,
}: {
  active?: boolean;
  payload?: Array<{ dataKey: string; value: number | null; payload: Row }>;
  label?: string;
  chains: string[];
}) {
  if (!active || !payload?.[0]) return null;
  const row = payload[0].payload;

  return (
    <div className="rounded-lg bg-bg-secondary border border-border px-3 py-2 shadow-lg text-xs">
      <div className="text-text-muted mb-1.5 font-medium">{label}</div>
      {chains.map((chain) => {
        const failRate = row[`${chain}_failRate`] as number | null;
        const gasPrice = row[`${chain}_gasPrice`] as number | null;
        if (failRate == null && gasPrice == null) return null;
        return (
          <div key={chain} className="mb-1 last:mb-0">
            <div className="flex items-center gap-1.5 mb-0.5">
              <span
                className="w-2 h-2 rounded-full shrink-0"
                style={{ backgroundColor: getChainColor(chain) }}
              />
              <span className="font-medium text-text-primary">
                {getChainDisplayName(chain)}
              </span>
            </div>
            <div className="grid grid-cols-2 gap-x-4 gap-y-0 pl-3.5 text-text-secondary">
              <span>Fail rate:</span>
              <span className="font-mono text-right">
                {failRate != null ? `${failRate.toFixed(2)}%` : "—"}
              </span>
              <span>Avg gas:</span>
              <span className="font-mono text-right">
                {gasPrice != null ? `${gasPrice.toFixed(2)} Gwei` : "—"}
              </span>
            </div>
          </div>
        );
      })}
    </div>
  );
}
