import { AreaChart, Area, ResponsiveContainer } from "recharts";
import { getChainColor } from "@/utils/format";
import type { IndexerProgressMessage } from "@/types";

interface ThroughputSparklineProps {
  chain: string;
  history: IndexerProgressMessage[];
}

export function ThroughputSparkline({ chain, history }: ThroughputSparklineProps) {
  const color = getChainColor(chain);
  const data = history.map((msg, i) => ({ i, bps: msg.blocksPerSecond }));

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
