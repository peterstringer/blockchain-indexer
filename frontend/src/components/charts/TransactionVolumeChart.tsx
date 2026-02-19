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
import type { BlockIndexedMessage } from "@/types";
import {
  getChainColor,
  getChainDisplayName,
  formatBlock,
} from "@/utils/format";

interface TransactionVolumeChartProps {
  blocksByChain: Record<string, BlockIndexedMessage[]>;
  chainKeys: string[];
}

export function TransactionVolumeChart({
  blocksByChain,
  chainKeys,
}: TransactionVolumeChartProps) {
  const data = useMemo(() => {
    const merged: {
      blockLabel: string;
      timestamp: number;
      [chain: string]: number | string;
    }[] = [];

    for (const chain of chainKeys) {
      const blocks = (blocksByChain[chain] ?? []).slice(0, 15);
      for (const b of blocks) {
        const ts = new Date(b.timestamp).getTime();
        merged.push({
          blockLabel: `${chain.slice(0, 3).toUpperCase()} #${formatBlock(b.blockNumber)}`,
          timestamp: ts,
          [chain]: b.transactionCount,
        });
      }
    }

    merged.sort((a, b) => a.timestamp - b.timestamp);
    return merged.slice(-20);
  }, [blocksByChain, chainKeys]);

  return (
    <Card>
      <CardHeader
        title="Transaction Volume"
        subtitle="Transactions per block by chain"
      />
      <div className="h-64">
        {data.length === 0 ? (
          <div className="flex items-center justify-center h-full text-xs text-text-muted">
            No block data yet
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data}>
              <CartesianGrid
                strokeDasharray="3 3"
                stroke="var(--color-border)"
                vertical={false}
              />
              <XAxis
                dataKey="blockLabel"
                tick={{ fontSize: 9, fill: "var(--color-text-muted)" }}
                axisLine={false}
                tickLine={false}
                interval="preserveStartEnd"
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
