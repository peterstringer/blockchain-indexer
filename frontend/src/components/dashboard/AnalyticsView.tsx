import { useState, useCallback } from "react";
import { Activity, Clock } from "lucide-react";
import { MultiChainThroughputChart } from "@/components/charts/ThroughputChart";
import { GasPriceChart } from "@/components/charts/GasPriceChart";
import { TransactionVolumeChart } from "@/components/charts/TransactionVolumeChart";
import { GasChart } from "@/components/charts/GasChart";
import { DailyGasPriceChart } from "@/components/charts/historical/DailyGasPriceChart";
import { HourlyGasPatternChart } from "@/components/charts/historical/HourlyGasPatternChart";
import { BlockFullnessChart } from "@/components/charts/historical/BlockFullnessChart";
import { CrossChainComparisonChart } from "@/components/charts/historical/CrossChainComparisonChart";
import { TransactionTypeChart } from "@/components/charts/historical/TransactionTypeChart";
import { DateRangePicker } from "@/components/common/DateRangePicker";
import { useChainUpdates, useProgressHistory } from "@/hooks/useWebSocket";
import {
  useDataAvailability,
  useDailyGasPrices,
  useHourlyGasPatterns,
  useBlockFullness,
  useCrossChainComparison,
  useTransactionTypeAnalysis,
} from "@/hooks/useHistoricalAnalytics";
import type { IndexerStatus, BlockIndexedMessage, IndexerProgressMessage } from "@/types";

type SubTab = "realtime" | "historical";
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
  const [subTab, setSubTab] = useState<SubTab>("historical");

  // Collect real-time data from all chains (hooks must be called unconditionally)
  const blocksByChain: Record<string, BlockIndexedMessage[]> = {};
  const historyByChain: Record<string, IndexerProgressMessage[]> = {};

  for (const key of chainKeys) {
    const { recentBlocks } = useChainUpdates(key);
    blocksByChain[key] = recentBlocks;
    historyByChain[key] = useProgressHistory(key);
  }

  return (
    <div className="space-y-4">
      {/* Sub-tab navigation */}
      <div className="flex items-center gap-1 rounded-lg border border-border p-1 w-fit">
        <button
          onClick={() => setSubTab("realtime")}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${
            subTab === "realtime"
              ? "bg-accent-purple/20 text-accent-purple"
              : "text-text-muted hover:text-text-secondary"
          }`}
        >
          <Activity size={14} />
          Real-Time
        </button>
        <button
          onClick={() => setSubTab("historical")}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${
            subTab === "historical"
              ? "bg-accent-purple/20 text-accent-purple"
              : "text-text-muted hover:text-text-secondary"
          }`}
        >
          <Clock size={14} />
          Historical
        </button>
      </div>

      {subTab === "realtime" ? (
        <RealTimeCharts
          chainKeys={chainKeys}
          blocksByChain={blocksByChain}
          historyByChain={historyByChain}
        />
      ) : (
        <HistoricalCharts chainKeys={chainKeys} />
      )}
    </div>
  );
}

// ---- Real-Time sub-tab (existing charts) ----

function RealTimeCharts({
  chainKeys,
  blocksByChain,
  historyByChain,
}: {
  chainKeys: string[];
  blocksByChain: Record<string, BlockIndexedMessage[]>;
  historyByChain: Record<string, IndexerProgressMessage[]>;
}) {
  return (
    <div className="space-y-6">
      <MultiChainThroughputChart
        historyByChain={historyByChain}
        chainKeys={chainKeys}
      />
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <GasPriceChart blocksByChain={blocksByChain} chainKeys={chainKeys} />
        <TransactionVolumeChart
          blocksByChain={blocksByChain}
          chainKeys={chainKeys}
        />
      </div>
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {chainKeys.map((key) => (
          <GasChart key={key} chain={key} blocks={blocksByChain[key] ?? []} />
        ))}
      </div>
    </div>
  );
}

// ---- Historical sub-tab (new analytics) ----

function HistoricalCharts({ chainKeys }: { chainKeys: string[] }) {
  const { data: availability } = useDataAvailability();

  // Determine the earliest available date across chains
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

  // Fetch all analytics data
  const { data: dailyGas } = useDailyGasPrices(from, to, chain);
  const { data: hourlyGas } = useHourlyGasPatterns(from, to, chain);
  const { data: fullness } = useBlockFullness(from, to);
  const { data: crossChain } = useCrossChainComparison(from, to);
  const { data: txTypes } = useTransactionTypeAnalysis(from, to, chain);

  // Determine which chains have data in the results
  const activeChains = chain
    ? [chain]
    : dailyGas
      ? [...new Set(dailyGas.map((d) => d.chain))]
      : chainKeys;

  const hasData = availability && availability.length > 0;

  return (
    <div className="space-y-4">
      {/* Date range picker */}
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
        {/* Data availability summary */}
        {availability && availability.length > 0 && (
          <div className="flex flex-wrap gap-3 mt-2">
            {availability.map((a) => (
              <span key={a.chain} className="text-[10px] text-text-muted">
                {a.chain}: {a.blockCount.toLocaleString()} blocks ({a.earliestDate} → {a.latestDate})
              </span>
            ))}
          </div>
        )}
      </div>

      {!hasData ? (
        <div className="flex flex-col items-center justify-center py-16 text-text-muted">
          <Clock size={40} className="mb-3 opacity-30" />
          <p className="text-sm font-medium">No historical data yet</p>
          <p className="text-xs mt-1">
            Start indexing to collect block analytics for historical charts
          </p>
        </div>
      ) : (
        <div className="space-y-4">
          {/* Daily gas prices — full width */}
          <DailyGasPriceChart
            data={dailyGas ?? []}
            chainKeys={activeChains}
          />

          {/* Hourly patterns + Block fullness */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <HourlyGasPatternChart
              data={hourlyGas ?? []}
              chainKeys={activeChains}
            />
            <BlockFullnessChart data={fullness ?? []} />
          </div>

          {/* Cross-chain + Transaction types */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <CrossChainComparisonChart data={crossChain ?? []} />
            <TransactionTypeChart
              data={txTypes ?? []}
              selectedChain={chain}
            />
          </div>
        </div>
      )}
    </div>
  );
}
