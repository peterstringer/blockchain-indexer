import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
  Legend,
} from "recharts";
import { Card, CardHeader } from "@/components/common/Card";
import type { IndexerProgressMessage } from "@/types";
import { getChainColor, getChainDisplayName } from "@/utils/format";

/** Single-chain throughput chart */
interface SingleChainProps {
  chain: string;
  history: IndexerProgressMessage[];
}

export function ThroughputChart({ chain, history }: SingleChainProps) {
  const data = history.map((msg, i) => ({
    index: i,
    bps: msg.blocksPerSecond,
    time: new Date(msg.timestamp).toLocaleTimeString(),
  }));

  const color = getChainColor(chain);

  return (
    <Card>
      <CardHeader
        title={`${getChainDisplayName(chain)} Throughput`}
        subtitle="Blocks per second over time"
      />
      <div className="h-48">
        {data.length < 2 ? (
          <div className="flex items-center justify-center h-full text-xs text-text-muted">
            Collecting data...
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={data}>
              <defs>
                <linearGradient
                  id={`gradient-${chain}`}
                  x1="0"
                  y1="0"
                  x2="0"
                  y2="1"
                >
                  <stop offset="0%" stopColor={color} stopOpacity={0.3} />
                  <stop offset="100%" stopColor={color} stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid
                strokeDasharray="3 3"
                stroke="var(--color-border)"
                vertical={false}
              />
              <XAxis
                dataKey="time"
                tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
              />
              <YAxis
                tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
                width={40}
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
              <Area
                type="monotone"
                dataKey="bps"
                stroke={color}
                strokeWidth={2}
                fill={`url(#gradient-${chain})`}
                name="Blocks/s"
              />
            </AreaChart>
          </ResponsiveContainer>
        )}
      </div>
    </Card>
  );
}

/** Multi-chain throughput overlay chart */
interface MultiChainProps {
  historyByChain: Record<string, IndexerProgressMessage[]>;
  chainKeys: string[];
}

export function MultiChainThroughputChart({
  historyByChain,
  chainKeys,
}: MultiChainProps) {
  // Merge all histories into a single timeline
  const allPoints: { time: string; timestamp: number; [chain: string]: number | string }[] = [];

  for (const chain of chainKeys) {
    const history = historyByChain[chain] ?? [];
    for (const msg of history) {
      const ts = new Date(msg.timestamp).getTime();
      const timeLabel = new Date(msg.timestamp).toLocaleTimeString();

      let existing = allPoints.find(
        (p) => Math.abs((p.timestamp as number) - ts) < 1000
      );
      if (!existing) {
        existing = { time: timeLabel, timestamp: ts } as typeof allPoints[number];
        allPoints.push(existing);
      }
      existing[chain] = msg.blocksPerSecond;
    }
  }

  allPoints.sort((a, b) => (a.timestamp as number) - (b.timestamp as number));

  return (
    <Card>
      <CardHeader
        title="Combined Throughput"
        subtitle="Blocks per second across all chains"
      />
      <div className="h-64">
        {allPoints.length < 2 ? (
          <div className="flex items-center justify-center h-full text-xs text-text-muted">
            Collecting data...
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={allPoints}>
              <defs>
                {chainKeys.map((chain) => (
                  <linearGradient
                    key={chain}
                    id={`gradient-multi-${chain}`}
                    x1="0"
                    y1="0"
                    x2="0"
                    y2="1"
                  >
                    <stop
                      offset="0%"
                      stopColor={getChainColor(chain)}
                      stopOpacity={0.2}
                    />
                    <stop
                      offset="100%"
                      stopColor={getChainColor(chain)}
                      stopOpacity={0}
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
                dataKey="time"
                tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
              />
              <YAxis
                tick={{ fontSize: 10, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
                width={40}
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
                  key={chain}
                  type="monotone"
                  dataKey={chain}
                  stroke={getChainColor(chain)}
                  strokeWidth={2}
                  fill={`url(#gradient-multi-${chain})`}
                  name={getChainDisplayName(chain)}
                  connectNulls
                />
              ))}
            </AreaChart>
          </ResponsiveContainer>
        )}
      </div>
    </Card>
  );
}
