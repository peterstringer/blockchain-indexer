import { useState, useCallback } from "react";
import { Clock, Flame, Box, GitBranch, AlertTriangle, Grid3x3 } from "lucide-react";
import { DateRangePicker } from "@/components/common/DateRangePicker";
import { GasMarketChart } from "@/components/charts/historical/GasMarketChart";
import { BlockSpaceDemandChart } from "@/components/charts/historical/BlockSpaceDemandChart";
import { TxTypeEvolutionChart } from "@/components/charts/historical/TxTypeEvolutionChart";
import { FailureAnalysisChart } from "@/components/charts/historical/FailureAnalysisChart";
import { TxDensityHeatmap } from "@/components/charts/historical/TxDensityHeatmap";
import { useDataAvailability, useGasMarket, useDailyBlockFullness, useDailyTransactionTypes, useDailyFailureRate, useTxDensityHeatmap } from "@/hooks/useHistoricalAnalytics";
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
  const { data: gasMarket, isLoading: gasMarketLoading } = useGasMarket(from, to, chain);
  const { data: dailyFullness, isLoading: fullnessLoading } = useDailyBlockFullness(from, to, chain);
  const { data: dailyTxTypes, isLoading: txTypesLoading } = useDailyTransactionTypes(from, to, chain);
  const { data: dailyFailure, isLoading: failureLoading } = useDailyFailureRate(from, to, chain);
  const { data: txDensity, isLoading: densityLoading } = useTxDensityHeatmap(from, to, chain);

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
            loading={gasMarketLoading}
          >
            <GasMarketChart data={gasMarket ?? []} selectedChain={chain} />
          </AnalyticsPanel>

          {/* 2. Block Space Demand */}
          <AnalyticsPanel
            icon={<Box className="w-4 h-4" />}
            title="Block Space Demand"
            loading={fullnessLoading || gasMarketLoading}
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
            loading={txTypesLoading}
          >
            <TxTypeEvolutionChart data={dailyTxTypes ?? []} />
          </AnalyticsPanel>

          {/* 4. Failure Analysis */}
          <AnalyticsPanel
            icon={<AlertTriangle className="w-4 h-4" />}
            title="Failure Analysis"
            subtitle="Overlay of failed transaction rate and gas price to identify correlation during congestion periods."
            loading={failureLoading}
          >
            <FailureAnalysisChart data={dailyFailure ?? []} />
          </AnalyticsPanel>

          {/* 5. Transaction Density Heatmap */}
          <AnalyticsPanel
            icon={<Grid3x3 className="w-4 h-4" />}
            title="Transaction Density Heatmap"
            loading={densityLoading}
          >
            <TxDensityHeatmap data={txDensity ?? []} />
          </AnalyticsPanel>
        </div>
      )}
    </div>
  );
}

function AnalyticsPanel({
  icon,
  title,
  subtitle,
  loading,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  subtitle?: string;
  loading?: boolean;
  children?: React.ReactNode;
}) {
  return (
    <div className="w-full rounded-xl bg-bg-card border border-border p-5">
      <div className="mb-3">
        <div className="flex items-center gap-2">
          <span className="text-text-muted">{icon}</span>
          <h3 className="text-sm font-semibold text-text-primary">{title}</h3>
        </div>
        {subtitle && (
          <p className="text-[11px] text-text-muted mt-1 ml-6">{subtitle}</p>
        )}
      </div>
      {loading ? <ChartSkeleton /> : children}
    </div>
  );
}

const SKELETON_HEIGHTS = [42, 58, 52, 67, 61, 74, 69, 78, 72, 55, 64, 57, 46, 53, 62, 70, 75, 67, 59, 51, 40, 49, 56, 63];

function ChartSkeleton() {
  return (
    <div className="animate-pulse">
      <div className="h-80 flex gap-1">
        {/* Y-axis labels */}
        <div className="w-10 flex flex-col justify-between py-3">
          {Array.from({ length: 5 }, (_, i) => (
            <div key={i} className="h-1.5 w-7 bg-border/30 rounded" />
          ))}
        </div>
        {/* Chart area */}
        <div className="flex-1 flex items-end gap-[3px] border-l border-b border-border/20 px-2">
          {SKELETON_HEIGHTS.map((h, i) => (
            <div
              key={i}
              className="flex-1 rounded-t-sm bg-border/20"
              style={{ height: `${h}%` }}
            />
          ))}
        </div>
      </div>
      {/* X-axis labels */}
      <div className="flex justify-between mt-2 ml-11">
        {Array.from({ length: 5 }, (_, i) => (
          <div key={i} className="h-1.5 w-8 bg-border/30 rounded" />
        ))}
      </div>
    </div>
  );
}
