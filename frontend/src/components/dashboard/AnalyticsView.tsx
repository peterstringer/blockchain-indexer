import { useState, useCallback } from "react";
import { Clock, Flame, Box, GitBranch, AlertTriangle, Grid3x3 } from "lucide-react";
import { DateRangePicker } from "@/components/common/DateRangePicker";
import { GasMarketChart } from "@/components/charts/historical/GasMarketChart";
import { BlockSpaceDemandChart } from "@/components/charts/historical/BlockSpaceDemandChart";
import { useDataAvailability, useGasMarket, useDailyBlockFullness } from "@/hooks/useHistoricalAnalytics";
import type { IndexerStatus } from "@/types";

type Preset = "7d" | "30d" | "90d" | "all";

function toDateStr(d: Date): string {
  return d.toISOString().slice(0, 10);
}

function getDateRange(preset: Preset, earliestDate?: string): { from: string; to: string } {
  const to = toDateStr(new Date());
  if (preset === "all" && earliestDate) {
    return { from: earliestDate, to };
  }
  const days = preset === "7d" ? 7 : preset === "30d" ? 30 : 90;
  const fromDate = new Date();
  fromDate.setDate(fromDate.getDate() - days);
  return { from: toDateStr(fromDate), to };
}

interface AnalyticsViewProps {
  status: IndexerStatus;
}

export function AnalyticsView({ status }: AnalyticsViewProps) {
  const chainKeys = Object.keys(status.chains);
  const { data: availability } = useDataAvailability();

  const earliestDate = availability?.reduce<string | undefined>(
    (min, d) => (!min || d.earliestDate < min ? d.earliestDate : min),
    undefined
  );

  const [preset, setPreset] = useState<Preset>("30d");
  const [chain, setChain] = useState<string | undefined>(undefined);

  const defaultRange = getDateRange(preset, earliestDate);
  const [from, setFrom] = useState(defaultRange.from);
  const [to, setTo] = useState(defaultRange.to);

  const handlePresetChange = useCallback(
    (p: Preset) => {
      setPreset(p);
      const range = getDateRange(p, earliestDate);
      setFrom(range.from);
      setTo(range.to);
    },
    [earliestDate]
  );

  const hasData = availability && availability.length > 0;

  // Fetch panel data
  const { data: gasMarket } = useGasMarket(from, to, chain);
  const { data: dailyFullness } = useDailyBlockFullness(from, to, chain);

  return (
    <div className="flex flex-col min-h-0">
      {/* Sticky controls bar */}
      <div className="sticky top-0 z-10 bg-bg-primary/95 backdrop-blur-sm pb-3 -mx-1 px-1">
        <div className="bg-bg-secondary/50 rounded-lg p-3 border border-border">
          <DateRangePicker
            from={from}
            to={to}
            onFromChange={setFrom}
            onToChange={setTo}
            chain={chain}
            onChainChange={setChain}
            chainKeys={chainKeys}
            activePreset={preset}
            onPresetChange={handlePresetChange}
          />
          {availability && availability.length > 0 && (
            <div className="flex flex-wrap gap-3 mt-2">
              {availability.map((a) => (
                <span key={a.chain} className="text-[10px] text-text-muted">
                  {a.chain}: {a.blockCount.toLocaleString()} blocks ({a.earliestDate} &rarr; {a.latestDate})
                </span>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Panel content */}
      {!hasData ? (
        <div className="flex flex-col items-center justify-center py-16 text-text-muted">
          <Clock size={40} className="mb-3 opacity-30" />
          <p className="text-sm font-medium">No historical data yet</p>
          <p className="text-xs mt-1">
            Start indexing to collect block analytics for historical charts
          </p>
        </div>
      ) : (
        <div className="space-y-4 mt-1">
          {/* 1. Gas Market */}
          <AnalyticsPanel
            icon={<Flame className="w-4 h-4" />}
            title="Gas Market"
          >
            <GasMarketChart data={gasMarket ?? []} selectedChain={chain} />
          </AnalyticsPanel>

          {/* 2. Block Space Demand */}
          <AnalyticsPanel
            icon={<Box className="w-4 h-4" />}
            title="Block Space Demand"
          >
            <BlockSpaceDemandChart
              fullnessData={dailyFullness ?? []}
              gasMarketData={gasMarket ?? []}
            />
          </AnalyticsPanel>

          {/* 3. Transaction Type Evolution */}
          <AnalyticsPanel
            icon={<GitBranch className="w-4 h-4" />}
            title="Transaction Type Evolution"
            placeholder="Breakdown of Legacy, EIP-1559, and contract creation transactions over time — adoption curves and average gas cost per type."
          />

          {/* 4. Failure Analysis */}
          <AnalyticsPanel
            icon={<AlertTriangle className="w-4 h-4" />}
            title="Failure Analysis"
            placeholder="Failed transaction rates over time, failure counts by chain, and correlation between gas prices and failure frequency."
          />

          {/* 5. Transaction Density Heatmap */}
          <AnalyticsPanel
            icon={<Grid3x3 className="w-4 h-4" />}
            title="Transaction Density Heatmap"
            placeholder="Hour-of-day vs day-of-week heatmap showing when each chain processes the most transactions — peak usage patterns."
          />
        </div>
      )}
    </div>
  );
}

function AnalyticsPanel({
  icon,
  title,
  placeholder,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  placeholder?: string;
  children?: React.ReactNode;
}) {
  return (
    <div className="w-full rounded-xl bg-bg-card border border-border p-5">
      <div className="flex items-center gap-2 mb-3">
        <span className="text-text-muted">{icon}</span>
        <h3 className="text-sm font-semibold text-text-primary">{title}</h3>
      </div>
      {children ?? (
        <div>
          <p className="text-xs text-text-muted leading-relaxed mb-4">{placeholder}</p>
          <div className="flex items-center justify-center h-48 rounded-lg border border-dashed border-border">
            <span className="text-xs text-text-muted">Chart placeholder</span>
          </div>
        </div>
      )}
    </div>
  );
}
