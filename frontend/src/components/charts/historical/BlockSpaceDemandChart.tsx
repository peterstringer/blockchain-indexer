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
  ReferenceLine,
} from "recharts";
import type { BlockFullnessDaily, GasMarketDaily } from "@/types";
import { getChainColor, getChainDisplayName, formatDateShort } from "@/utils/format";

interface BlockSpaceDemandChartProps {
  fullnessData: BlockFullnessDaily[];
  gasMarketData: GasMarketDaily[];
}

type Row = Record<string, number | string | null>;

export function BlockSpaceDemandChart({
  fullnessData,
  gasMarketData,
}: BlockSpaceDemandChartProps) {
  const chains = useMemo(() => {
    const fromFullness = fullnessData.map((d) => d.chain);
    const fromGas = gasMarketData.map((d) => d.chain);
    return [...new Set([...fromFullness, ...fromGas])];
  }, [fullnessData, gasMarketData]);

  const chartData = useMemo(() => {
    const byDate = new Map<string, Row>();

    for (const d of fullnessData) {
      if (!byDate.has(d.date)) {
        byDate.set(d.date, { date: d.date });
      }
      const row = byDate.get(d.date)!;
      row[`${d.chain}_fullness`] = d.avgFullnessPercent;
    }

    for (const d of gasMarketData) {
      if (!byDate.has(d.date)) {
        byDate.set(d.date, { date: d.date });
      }
      const row = byDate.get(d.date)!;
      row[`${d.chain}_baseFee`] = d.avgBaseFeeGwei;
    }

    return Array.from(byDate.values()).sort((a, b) =>
      (a.date as string).localeCompare(b.date as string)
    );
  }, [fullnessData, gasMarketData]);

  if (chartData.length === 0) {
    return (
      <div className="flex items-center justify-center h-72 text-xs text-text-muted">
        No block space data for the selected range
      </div>
    );
  }

  return (
    <div>
      <div className="h-80">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={chartData}>
            <defs>
              {chains.map((chain) => (
                <linearGradient
                  key={`fullness-${chain}`}
                  id={`fullness-fill-${chain}`}
                  x1="0"
                  y1="0"
                  x2="0"
                  y2="1"
                >
                  <stop offset="0%" stopColor={getChainColor(chain)} stopOpacity={0.2} />
                  <stop offset="100%" stopColor={getChainColor(chain)} stopOpacity={0.05} />
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
              tickFormatter={formatDateShort}
              tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
              axisLine={false}
              tickLine={false}
              interval="preserveStartEnd"
            />
            <YAxis
              yAxisId="fullness"
              domain={[0, 100]}
              tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
              axisLine={false}
              tickLine={false}
              width={40}
              tickFormatter={(v: number) => `${v}%`}
            />
            <YAxis
              yAxisId="gwei"
              orientation="right"
              tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
              axisLine={false}
              tickLine={false}
              width={40}
            />
            <Tooltip content={<DemandTooltip chains={chains} />} />

            {/* EIP-1559 target reference line at 50% */}
            <ReferenceLine
              yAxisId="fullness"
              y={50}
              stroke="var(--color-text-muted)"
              strokeDasharray="6 4"
              strokeOpacity={0.5}
              label={{
                value: "EIP-1559 target",
                position: "insideTopRight",
                style: { fontSize: 10, fill: "var(--color-text-muted)" },
              }}
            />

            {/* Block fullness areas (left axis) */}
            {chains.map((chain) => (
              <Area
                key={`${chain}-fullness`}
                yAxisId="fullness"
                type="monotone"
                dataKey={`${chain}_fullness`}
                stroke={getChainColor(chain)}
                strokeWidth={1.5}
                fill={`url(#fullness-fill-${chain})`}
                isAnimationActive={false}
                connectNulls
              />
            ))}

            {/* Base fee lines (right axis) */}
            {chains.map((chain) => (
              <Line
                key={`${chain}-baseFee`}
                yAxisId="gwei"
                type="monotone"
                dataKey={`${chain}_baseFee`}
                stroke={getChainColor(chain)}
                strokeWidth={2}
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
            <span className="inline-block w-3 h-2.5 bg-text-muted/15 rounded-sm" />
            Block fullness %
          </span>
          <span className="flex items-center gap-1.5">
            <span className="inline-block w-4 h-0.5 bg-text-muted rounded" />
            Base fee (Gwei)
          </span>
          <span className="flex items-center gap-1.5">
            <span className="inline-block w-4 h-0 border-t border-dashed border-text-muted" />
            50% target
          </span>
        </div>
      </div>
    </div>
  );
}

/** Custom tooltip showing fullness % and base fee for each chain */
function DemandTooltip({
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
      <div className="text-text-muted mb-1.5 font-medium">{label ? formatDateShort(label) : ""}</div>
      {chains.map((chain) => {
        const fullness = row[`${chain}_fullness`] as number | null;
        const baseFee = row[`${chain}_baseFee`] as number | null;
        if (fullness == null && baseFee == null) return null;
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
              <span>Fullness:</span>
              <span className="font-mono text-right">
                {fullness != null ? `${fullness.toFixed(1)}%` : "—"}
              </span>
              <span>Base fee:</span>
              <span className="font-mono text-right">
                {baseFee != null ? `${baseFee.toFixed(2)} Gwei` : "—"}
              </span>
            </div>
          </div>
        );
      })}
    </div>
  );
}
