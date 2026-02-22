import { useMemo, useState, useRef } from "react";
import type { TxDensityCell } from "@/types";
import { getChainColor, getChainDisplayName } from "@/utils/format";

interface TxDensityHeatmapProps {
  data: TxDensityCell[];
}

const DAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const DAY_FULL = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
const HOURS = Array.from({ length: 24 }, (_, i) => i);

interface CellData {
  avg: number;
  blocks: number;
}

/** Parse hex color to [r, g, b] */
function hexToRgb(hex: string): [number, number, number] {
  const h = hex.replace("#", "");
  return [
    parseInt(h.substring(0, 2), 16),
    parseInt(h.substring(2, 4), 16),
    parseInt(h.substring(4, 6), 16),
  ];
}

/** Resolve a CSS variable to its computed hex value */
function resolveColor(cssVar: string): string {
  if (!cssVar.startsWith("var(")) return cssVar;
  const name = cssVar.slice(4, -1);
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/** Interpolate from dark base to chain color based on 0-1 intensity */
function cellColor(chainColor: string, intensity: number): string {
  const resolved = resolveColor(chainColor);
  const [r, g, b] = hexToRgb(resolved);
  // On dark background: very low alpha for low values, high alpha for peaks
  const alpha = 0.05 + intensity * 0.85;
  return `rgba(${r}, ${g}, ${b}, ${alpha.toFixed(2)})`;
}

interface TooltipInfo {
  day: number;
  hour: number;
  avg: number;
  blocks: number;
  x: number;
  y: number;
}

export function TxDensityHeatmap({ data }: TxDensityHeatmapProps) {
  const [tooltip, setTooltip] = useState<(TooltipInfo & { chain: string }) | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const byChain = useMemo(() => {
    const map = new Map<string, Map<string, CellData>>();
    for (const d of data) {
      if (!map.has(d.chain)) map.set(d.chain, new Map());
      const grid = map.get(d.chain)!;
      grid.set(`${d.dayOfWeek}-${d.hour}`, {
        avg: d.avgTransactionCount ?? 0,
        blocks: d.totalBlocks,
      });
    }
    return map;
  }, [data]);

  const chains = useMemo(() => [...byChain.keys()].sort(), [byChain]);

  // Compute global min/max for consistent color scale across chains
  const { min, max } = useMemo(() => {
    let mn = Infinity;
    let mx = -Infinity;
    for (const grid of byChain.values()) {
      for (const cell of grid.values()) {
        if (cell.avg < mn) mn = cell.avg;
        if (cell.avg > mx) mx = cell.avg;
      }
    }
    return { min: mn === Infinity ? 0 : mn, max: mx === -Infinity ? 0 : mx };
  }, [byChain]);

  if (chains.length === 0) {
    return (
      <div className="flex items-center justify-center h-72 text-xs text-text-muted">
        No transaction density data for the selected range
      </div>
    );
  }

  const range = max - min || 1;

  function handleMouseEnter(
    e: React.MouseEvent,
    chain: string,
    day: number,
    hour: number,
    cell: CellData | undefined
  ) {
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const targetRect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    setTooltip({
      chain,
      day,
      hour,
      avg: cell?.avg ?? 0,
      blocks: cell?.blocks ?? 0,
      x: targetRect.left - rect.left + targetRect.width / 2,
      y: targetRect.top - rect.top,
    });
  }

  function handleMouseLeave() {
    setTooltip(null);
  }

  return (
    <div ref={containerRef} className="relative">
      {chains.map((chain) => {
        const grid = byChain.get(chain)!;
        const color = getChainColor(chain);
        return (
          <div key={chain} className="mb-4 last:mb-0">
            {chains.length > 1 && (
              <div className="flex items-center gap-1.5 mb-2">
                <span
                  className="w-2 h-2 rounded-full shrink-0"
                  style={{ backgroundColor: color }}
                />
                <span className="text-xs font-medium text-text-secondary">
                  {getChainDisplayName(chain)}
                </span>
              </div>
            )}
            <div className="grid" style={{ gridTemplateColumns: "40px repeat(24, 1fr)", gap: "2px" }}>
              {/* Hour labels row */}
              <div /> {/* empty corner */}
              {HOURS.map((h) => (
                <div
                  key={`h-${h}`}
                  className="text-center text-[9px] text-text-muted leading-tight pb-1"
                >
                  {h.toString().padStart(2, "0")}
                </div>
              ))}

              {/* Data rows */}
              {DAY_NAMES.map((dayName, dayIdx) => (
                <>
                  <div
                    key={`label-${dayIdx}`}
                    className="text-[10px] text-text-muted flex items-center justify-end pr-2 leading-none"
                  >
                    {dayName}
                  </div>
                  {HOURS.map((hour) => {
                    const cell = grid.get(`${dayIdx}-${hour}`);
                    const intensity = cell ? (cell.avg - min) / range : 0;
                    return (
                      <div
                        key={`${dayIdx}-${hour}`}
                        className="aspect-square rounded-[3px] cursor-crosshair transition-opacity"
                        style={{
                          backgroundColor: cell ? cellColor(color, intensity) : "var(--color-border)",
                          opacity: cell ? 1 : 0.15,
                          minHeight: "12px",
                        }}
                        onMouseEnter={(e) => handleMouseEnter(e, chain, dayIdx, hour, cell)}
                        onMouseLeave={handleMouseLeave}
                      />
                    );
                  })}
                </>
              ))}
            </div>
          </div>
        );
      })}

      {/* Color scale legend */}
      <div className="flex items-center gap-3 mt-3 px-1">
        <span className="text-[10px] text-text-muted">Low</span>
        <div className="flex gap-[1px] flex-1 max-w-[200px]">
          {Array.from({ length: 12 }, (_, i) => {
            const intensity = i / 11;
            const color = chains.length === 1
              ? getChainColor(chains[0]!)
              : "var(--color-accent-green)";
            return (
              <div
                key={i}
                className="h-2.5 flex-1 rounded-[2px]"
                style={{ backgroundColor: cellColor(color, intensity) }}
              />
            );
          })}
        </div>
        <span className="text-[10px] text-text-muted">High</span>
        <span className="text-[10px] text-text-muted ml-auto">
          Avg transactions per block &middot; UTC hours
        </span>
      </div>

      {/* Tooltip */}
      {tooltip && (
        <div
          className="absolute z-20 pointer-events-none rounded-lg bg-bg-secondary border border-border px-3 py-2 shadow-lg text-xs whitespace-nowrap"
          style={{
            left: tooltip.x,
            top: tooltip.y,
            transform: "translate(-50%, -100%) translateY(-8px)",
          }}
        >
          <div className="text-text-muted font-medium mb-1">
            {DAY_FULL[tooltip.day]} {tooltip.hour.toString().padStart(2, "0")}:00 UTC
          </div>
          {chains.length > 1 && (
            <div className="flex items-center gap-1.5 mb-1">
              <span
                className="w-2 h-2 rounded-full shrink-0"
                style={{ backgroundColor: getChainColor(tooltip.chain) }}
              />
              <span className="font-medium text-text-primary">
                {getChainDisplayName(tooltip.chain)}
              </span>
            </div>
          )}
          <div className="grid grid-cols-2 gap-x-3 gap-y-0 text-text-secondary">
            <span>Avg txs:</span>
            <span className="font-mono text-right text-text-primary">
              {tooltip.avg.toFixed(1)}
            </span>
            <span>Blocks:</span>
            <span className="font-mono text-right text-text-primary">
              {tooltip.blocks.toLocaleString()}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}
