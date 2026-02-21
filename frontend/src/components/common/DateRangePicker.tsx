import { getChainDisplayName } from "@/utils/format";

type Preset = "7d" | "30d" | "90d" | "all";

const PRESETS: { id: Preset; label: string }[] = [
  { id: "7d", label: "7D" },
  { id: "30d", label: "30D" },
  { id: "90d", label: "90D" },
  { id: "all", label: "All" },
];

interface DateRangePickerProps {
  from: string;
  to: string;
  onFromChange: (date: string) => void;
  onToChange: (date: string) => void;
  chain?: string;
  onChainChange?: (chain: string | undefined) => void;
  chainKeys?: string[];
  activePreset?: Preset;
  onPresetChange?: (preset: Preset) => void;
}

export function DateRangePicker({
  from,
  to,
  onFromChange,
  onToChange,
  chain,
  onChainChange,
  chainKeys,
  activePreset,
  onPresetChange,
}: DateRangePickerProps) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      {/* Date inputs */}
      <div className="flex items-center gap-2">
        <label className="text-[11px] text-text-muted">From</label>
        <input
          type="date"
          value={from}
          onChange={(e) => onFromChange(e.target.value)}
          className="text-[11px] bg-bg-primary border border-border rounded-md px-2 py-1.5 text-text-secondary"
        />
      </div>
      <div className="flex items-center gap-2">
        <label className="text-[11px] text-text-muted">To</label>
        <input
          type="date"
          value={to}
          onChange={(e) => onToChange(e.target.value)}
          className="text-[11px] bg-bg-primary border border-border rounded-md px-2 py-1.5 text-text-secondary"
        />
      </div>

      {/* Preset buttons */}
      {onPresetChange && (
        <div className="flex rounded-md border border-border overflow-hidden">
          {PRESETS.map(({ id, label }) => (
            <button
              key={id}
              onClick={() => onPresetChange(id)}
              className={`px-2.5 py-1.5 text-[11px] font-medium transition-colors ${
                activePreset === id
                  ? "bg-accent-purple/20 text-accent-purple"
                  : "text-text-muted hover:text-text-secondary"
              }`}
            >
              {label}
            </button>
          ))}
        </div>
      )}

      {/* Chain filter */}
      {onChainChange && chainKeys && (
        <select
          value={chain ?? "all"}
          onChange={(e) =>
            onChainChange(e.target.value === "all" ? undefined : e.target.value)
          }
          className="text-[11px] bg-bg-primary border border-border rounded-md px-2 py-1.5 text-text-secondary"
        >
          <option value="all">All Chains</option>
          {chainKeys.map((c) => (
            <option key={c} value={c}>
              {getChainDisplayName(c)}
            </option>
          ))}
        </select>
      )}
    </div>
  );
}
