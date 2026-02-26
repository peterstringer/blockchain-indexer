import { useState, useCallback, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { Download, FileSpreadsheet, FileDown, Check } from "lucide-react";
import { Card, CardHeader } from "@/components/common/Card";
import { DateRangePicker } from "@/components/common/DateRangePicker";
import { useToast } from "@/components/common/Toast";
import { fetchExportMetadata, buildExportUrl } from "@/services/api";
import type { IndexerStatus, ExportColumnDef } from "@/types";

type Preset = "7d" | "30d" | "90d" | "all";

function toDateStr(d: Date): string {
  return d.toISOString().slice(0, 10);
}

function getDateRange(preset: Preset, earliestDate?: string | null): { from: string; to: string } {
  const to = toDateStr(new Date());
  if (preset === "all" && earliestDate) {
    return { from: earliestDate, to };
  }
  const days = preset === "7d" ? 7 : preset === "30d" ? 30 : 90;
  const fromDate = new Date();
  fromDate.setDate(fromDate.getDate() - days);
  return { from: toDateStr(fromDate), to };
}

interface ExportViewProps {
  status: IndexerStatus;
}

export function ExportView({ status }: ExportViewProps) {
  const { toast } = useToast();
  const chainKeys = Object.keys(status.chains);

  const { data: metadata } = useQuery({
    queryKey: ["export-metadata"],
    queryFn: fetchExportMetadata,
    staleTime: 30_000,
  });

  // Date range state
  const [preset, setPreset] = useState<Preset>("all");
  const [chain, setChain] = useState<string | undefined>(undefined);
  const defaultRange = getDateRange("all", metadata?.earliestDate);
  const [from, setFrom] = useState(defaultRange.from);
  const [to, setTo] = useState(defaultRange.to);

  // Update from when metadata loads
  const earliestDate = metadata?.earliestDate;
  useState(() => {
    if (earliestDate && preset === "all") {
      setFrom(earliestDate);
    }
  });

  const handlePresetChange = useCallback(
    (p: Preset) => {
      setPreset(p);
      const range = getDateRange(p, metadata?.earliestDate);
      setFrom(range.from);
      setTo(range.to);
    },
    [metadata?.earliestDate]
  );

  // Column selection state
  const [selectedColumns, setSelectedColumns] = useState<Set<string>>(new Set());
  const [initialized, setInitialized] = useState(false);

  // Initialize columns from metadata
  if (metadata && !initialized) {
    setSelectedColumns(new Set(metadata.columns.map((c) => c.key)));
    setInitialized(true);
    if (metadata.earliestDate && preset === "all") {
      setFrom(metadata.earliestDate);
    }
  }

  // Format state
  const [format, setFormat] = useState<"csv" | "parquet">("csv");
  const [downloading, setDownloading] = useState(false);

  // Group columns
  const columnGroups = useMemo(() => {
    if (!metadata) return new Map<string, ExportColumnDef[]>();
    const groups = new Map<string, ExportColumnDef[]>();
    for (const col of metadata.columns) {
      const existing = groups.get(col.group);
      if (existing) {
        existing.push(col);
      } else {
        groups.set(col.group, [col]);
      }
    }
    return groups;
  }, [metadata]);

  const allSelected = metadata ? selectedColumns.size === metadata.columns.length : false;

  const toggleAll = () => {
    if (allSelected) {
      setSelectedColumns(new Set());
    } else if (metadata) {
      setSelectedColumns(new Set(metadata.columns.map((c) => c.key)));
    }
  };

  const toggleGroup = (group: string) => {
    const cols = columnGroups.get(group);
    if (!cols) return;
    const allGroupSelected = cols.every((c) => selectedColumns.has(c.key));
    const next = new Set(selectedColumns);
    for (const col of cols) {
      if (allGroupSelected) {
        next.delete(col.key);
      } else {
        next.add(col.key);
      }
    }
    setSelectedColumns(next);
  };

  const toggleColumn = (key: string) => {
    const next = new Set(selectedColumns);
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    setSelectedColumns(next);
  };

  const handleDownload = () => {
    if (selectedColumns.size === 0) {
      toast("error", "Select at least one column to export");
      return;
    }

    setDownloading(true);

    const url = buildExportUrl({
      from,
      to,
      chain,
      format,
      columns: Array.from(selectedColumns),
    });

    // Trigger native browser download via hidden anchor
    const a = document.createElement("a");
    a.href = url;
    a.download = "";
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);

    toast("success", `Downloading ${format.toUpperCase()} export...`);
    setTimeout(() => setDownloading(false), 2000);
  };

  const hasData = metadata && metadata.totalRows > 0;

  return (
    <div className="space-y-6">
      {/* Data Selection */}
      <Card>
        <CardHeader
          title="Data Selection"
          subtitle="Choose the chain, date range, and time period for your export"
          action={<Download className="w-4 h-4 text-text-muted" />}
        />
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
        {metadata && (
          <div className="flex flex-wrap gap-3 mt-3">
            <span className="text-[10px] text-text-muted">
              Available: {metadata.totalRows.toLocaleString()} rows
            </span>
            {metadata.earliestDate && metadata.latestDate && (
              <span className="text-[10px] text-text-muted">
                {metadata.earliestDate} &rarr; {metadata.latestDate}
              </span>
            )}
          </div>
        )}
      </Card>

      {/* Column Selection */}
      <Card>
        <CardHeader
          title="Column Selection"
          subtitle="Choose which data fields to include in the export"
        />
        {!hasData ? (
          <p className="text-xs text-text-muted text-center py-4">
            No indexed data available yet. Start indexing to collect block analytics.
          </p>
        ) : (
          <div className="space-y-4">
            {/* Select all toggle */}
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={allSelected}
                onChange={toggleAll}
                className="w-3.5 h-3.5 rounded border-border accent-accent-purple"
              />
              <span className="text-xs font-medium text-text-primary">
                Select All ({selectedColumns.size}/{metadata?.columns.length ?? 0})
              </span>
            </label>

            {/* Column groups */}
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {Array.from(columnGroups.entries()).map(([group, cols]) => {
                const groupAllSelected = cols.every((c) => selectedColumns.has(c.key));
                const groupSomeSelected = cols.some((c) => selectedColumns.has(c.key));
                return (
                  <div
                    key={group}
                    className="bg-bg-primary/50 rounded-lg p-3 border border-border/30"
                  >
                    {/* Group header */}
                    <label className="flex items-center gap-2 cursor-pointer mb-2">
                      <input
                        type="checkbox"
                        checked={groupAllSelected}
                        ref={(el) => {
                          if (el) el.indeterminate = groupSomeSelected && !groupAllSelected;
                        }}
                        onChange={() => toggleGroup(group)}
                        className="w-3.5 h-3.5 rounded border-border accent-accent-purple"
                      />
                      <span className="text-xs font-semibold text-text-primary">{group}</span>
                    </label>
                    {/* Individual columns */}
                    <div className="space-y-1.5 ml-5">
                      {cols.map((col) => (
                        <label
                          key={col.key}
                          className="flex items-center gap-2 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={selectedColumns.has(col.key)}
                            onChange={() => toggleColumn(col.key)}
                            className="w-3 h-3 rounded border-border accent-accent-purple"
                          />
                          <span className="text-[11px] text-text-secondary">{col.label}</span>
                        </label>
                      ))}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </Card>

      {/* Export Format & Download */}
      {hasData && (
        <Card>
          <CardHeader
            title="Export Format"
            subtitle="Choose the output format and download your data"
          />
          <div className="flex flex-wrap items-center gap-6">
            {/* Format toggle */}
            <div className="flex rounded-lg border border-border overflow-hidden">
              <button
                onClick={() => setFormat("csv")}
                className={`flex items-center gap-2 px-4 py-2.5 text-xs font-medium transition-colors ${
                  format === "csv"
                    ? "bg-accent-purple/15 text-accent-purple"
                    : "text-text-muted hover:text-text-secondary"
                }`}
              >
                <FileSpreadsheet className="w-4 h-4" />
                CSV
                {format === "csv" && <Check className="w-3 h-3" />}
              </button>
              <button
                onClick={() => setFormat("parquet")}
                className={`flex items-center gap-2 px-4 py-2.5 text-xs font-medium transition-colors border-l border-border ${
                  format === "parquet"
                    ? "bg-accent-purple/15 text-accent-purple"
                    : "text-text-muted hover:text-text-secondary"
                }`}
              >
                <FileDown className="w-4 h-4" />
                Parquet
                {format === "parquet" && <Check className="w-3 h-3" />}
              </button>
            </div>

            {/* Info */}
            <div className="flex flex-col gap-0.5">
              <span className="text-[11px] text-text-muted">
                {selectedColumns.size} column{selectedColumns.size !== 1 ? "s" : ""} selected
              </span>
              <span className="text-[11px] text-text-muted">
                {chain ? chain : "All chains"} &middot; {from} to {to}
              </span>
            </div>

            {/* Download button */}
            <button
              onClick={handleDownload}
              disabled={downloading || selectedColumns.size === 0}
              className="flex items-center gap-2 px-5 py-2.5 text-sm font-medium rounded-lg bg-accent-purple text-white hover:bg-accent-purple/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed ml-auto"
            >
              <Download className="w-4 h-4" />
              {downloading ? "Downloading..." : "Download"}
            </button>
          </div>
        </Card>
      )}
    </div>
  );
}
