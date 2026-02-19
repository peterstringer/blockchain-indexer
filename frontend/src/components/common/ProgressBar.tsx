interface ProgressBarProps {
  percent: number;
  color?: string;
  showLabel?: boolean;
  eta?: string | null;
  size?: "sm" | "md";
}

export function ProgressBar({
  percent,
  color = "var(--color-accent-blue)",
  showLabel = true,
  eta,
  size = "md",
}: ProgressBarProps) {
  const clamped = Math.min(100, Math.max(0, percent));
  const height = size === "sm" ? "h-1.5" : "h-2.5";

  return (
    <div className="w-full">
      {showLabel && (
        <div className="flex justify-between mb-1">
          <span className="text-xs text-text-muted">
            {eta ? `ETA: ${eta}` : "Progress"}
          </span>
          <span className="text-xs font-mono text-text-secondary">
            {clamped.toFixed(1)}%
          </span>
        </div>
      )}
      <div className={`w-full ${height} bg-bg-primary rounded-full overflow-hidden`}>
        <div
          className={`${height} rounded-full transition-all duration-500 ease-out`}
          style={{ width: `${clamped}%`, backgroundColor: color }}
        />
      </div>
    </div>
  );
}
