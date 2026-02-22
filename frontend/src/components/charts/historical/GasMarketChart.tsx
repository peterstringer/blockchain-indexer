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
} from "recharts";
import type { GasMarketDaily } from "@/types";
import { getChainColor, getChainDisplayName } from "@/utils/format";

interface GasMarketChartProps {
  data: GasMarketDaily[];
  selectedChain?: string;
}

type Row = Record<string, number | string | null>;

export function GasMarketChart({ data, selectedChain }: GasMarketChartProps) {
  const chains = useMemo(
    () => [...new Set(data.map((d) => d.chain))],
    [data]
  );

  const singleChain = selectedChain ?? (chains.length === 1 ? chains[0] : undefined);

  const chartData = useMemo(() => {
    const byDate = new Map<string, Row>();

    for (const d of data) {
      if (!byDate.has(d.date)) {
        byDate.set(d.date, { date: d.date });
      }
      const row = byDate.get(d.date)!;
      row[`${d.chain}_baseFee`] = d.avgBaseFeeGwei;
      row[`${d.chain}_effectiveGas`] = d.avgEffectiveGasPriceGwei;
      row[`${d.chain}_minBase`] = d.minBaseFeeGwei;
      row[`${d.chain}_maxBase`] = d.maxBaseFeeGwei;
    }

    return Array.from(byDate.values()).sort((a, b) =>
      (a.date as string).localeCompare(b.date as string)
    );
  }, [data]);

  if (chartData.length === 0) {
    return (
      <div className="flex items-center justify-center h-72 text-xs text-text-muted">
        No gas market data for the selected range
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
                  key={`priority-${chain}`}
                  id={`priority-fill-${chain}`}
                  x1="0"
                  y1="0"
                  x2="0"
                  y2="1"
                >
                  <stop offset="0%" stopColor={getChainColor(chain)} stopOpacity={0.15} />
                  <stop offset="100%" stopColor={getChainColor(chain)} stopOpacity={0.05} />
                </linearGradient>
              ))}
              {singleChain && (
                <linearGradient id="minmax-band" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={getChainColor(singleChain)} stopOpacity={0.08} />
                  <stop offset="100%" stopColor={getChainColor(singleChain)} stopOpacity={0.03} />
                </linearGradient>
              )}
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
            <Tooltip content={<GasMarketTooltip chains={chains} />} />

            {/* Single-chain min/max base fee band (behind everything) */}
            {singleChain && (
              <Area
                type="monotone"
                dataKey={`${singleChain}_maxBase`}
                stroke="none"
                fill="url(#minmax-band)"
                isAnimationActive={false}
                connectNulls
              />
            )}

            {/* Priority fee spread: area between baseFee and effectiveGas */}
            {chains.map((chain) => (
              <Area
                key={`${chain}-spread`}
                type="monotone"
                dataKey={`${chain}_effectiveGas`}
                stroke="none"
                fill={`url(#priority-fill-${chain})`}
                isAnimationActive={false}
                connectNulls
              />
            ))}

            {/* Base fee line (solid) */}
            {chains.map((chain) => (
              <Line
                key={`${chain}-base`}
                type="monotone"
                dataKey={`${chain}_baseFee`}
                stroke={getChainColor(chain)}
                strokeWidth={2}
                dot={false}
                connectNulls
                isAnimationActive={false}
              />
            ))}

            {/* Effective gas price line (dashed) */}
            {chains.map((chain) => (
              <Line
                key={`${chain}-effective`}
                type="monotone"
                dataKey={`${chain}_effectiveGas`}
                stroke={getChainColor(chain)}
                strokeWidth={1.5}
                strokeDasharray="6 3"
                dot={false}
                connectNulls
                isAnimationActive={false}
              />
            ))}

            {/* Single-chain min base fee (thin dotted) */}
            {singleChain && (
              <Line
                type="monotone"
                dataKey={`${singleChain}_minBase`}
                stroke={getChainColor(singleChain)}
                strokeWidth={1}
                strokeDasharray="2 3"
                strokeOpacity={0.4}
                dot={false}
                connectNulls
                isAnimationActive={false}
              />
            )}
            {singleChain && (
              <Line
                type="monotone"
                dataKey={`${singleChain}_maxBase`}
                stroke={getChainColor(singleChain)}
                strokeWidth={1}
                strokeDasharray="2 3"
                strokeOpacity={0.4}
                dot={false}
                connectNulls
                isAnimationActive={false}
              />
            )}
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
            Base fee
          </span>
          <span className="flex items-center gap-1.5">
            <span className="inline-block w-4 h-0.5 bg-text-muted rounded border-t border-dashed border-text-muted" style={{ borderStyle: "dashed" }} />
            Effective gas price
          </span>
          <span className="flex items-center gap-1.5">
            <span className="inline-block w-3 h-2.5 bg-text-muted/15 rounded-sm" />
            Priority fee (tip)
          </span>
        </div>
      </div>
    </div>
  );
}

/** Custom tooltip showing all chains' values for the hovered date */
function GasMarketTooltip({
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
        const base = row[`${chain}_baseFee`] as number | null;
        const effective = row[`${chain}_effectiveGas`] as number | null;
        const min = row[`${chain}_minBase`] as number | null;
        const max = row[`${chain}_maxBase`] as number | null;
        if (base == null && effective == null) return null;
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
              <span>Base fee:</span>
              <span className="font-mono text-right">{base != null ? `${base.toFixed(2)} Gwei` : "—"}</span>
              <span>Effective:</span>
              <span className="font-mono text-right">{effective != null ? `${effective.toFixed(2)} Gwei` : "—"}</span>
              {min != null && (
                <>
                  <span>Min base:</span>
                  <span className="font-mono text-right">{min.toFixed(2)} Gwei</span>
                </>
              )}
              {max != null && (
                <>
                  <span>Max base:</span>
                  <span className="font-mono text-right">{max.toFixed(2)} Gwei</span>
                </>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
