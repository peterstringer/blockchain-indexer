import { AreaChart, Area, Tooltip, ResponsiveContainer } from "recharts";
import { getChainColor } from "@/utils/format";
import type { IndexerProgressMessage } from "@/types";

interface ThroughputSparklineProps {
  chain: string;
  history: IndexerProgressMessage[];
}

interface SparkPoint {
  bps: number;
  time: string;
}

export function ThroughputSparkline({ chain, history }: ThroughputSparklineProps) {
  const color = getChainColor(chain);
  const data: SparkPoint[] = history.map((msg) => ({
    bps: msg.blocksPerSecond,
    time: msg.timestamp,
  }));

  return (
    <div className="h-12 w-full rounded-lg overflow-hidden bg-bg-card border border-border">
      {data.length < 2 ? (
        <div className="flex items-center justify-center h-full text-[10px] text-text-muted">
          Collecting throughput data...
        </div>
      ) : (
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 0, right: 0, bottom: 0, left: 0 }}>
            <defs>
              <linearGradient id={`spark-${chain}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={color} stopOpacity={0.4} />
                <stop offset="100%" stopColor={color} stopOpacity={0.05} />
              </linearGradient>
            </defs>
            <Tooltip
              content={<SparkTooltip />}
              cursor={{ stroke: color, strokeWidth: 1, strokeDasharray: "3 3" }}
            />
            <Area
              type="monotone"
              dataKey="bps"
              stroke={color}
              strokeWidth={1.5}
              fill={`url(#spark-${chain})`}
              isAnimationActive={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      )}
    </div>
  );
}

function SparkTooltip({ active, payload }: { active?: boolean; payload?: Array<{ payload: SparkPoint }> }) {
  if (!active || !payload?.[0]) return null;
  const { bps, time } = payload[0].payload;
  const formatted = new Date(time).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  return (
    <div className="rounded-md bg-bg-secondary border border-border px-2 py-1 text-[10px] leading-tight shadow-lg">
      <div className="font-mono text-text-primary">{bps.toFixed(1)} blocks/s</div>
      <div className="text-text-muted">{formatted}</div>
    </div>
  );
}
