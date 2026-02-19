import type { ReactNode } from "react";

interface MetricValueProps {
  label: string;
  value: string | number;
  icon?: ReactNode;
  trend?: "up" | "down" | "neutral";
}

export function MetricValue({ label, value, icon, trend }: MetricValueProps) {
  const trendColor =
    trend === "up"
      ? "text-accent-green"
      : trend === "down"
        ? "text-accent-red"
        : "text-text-primary";

  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-text-muted flex items-center gap-1.5">
        {icon}
        {label}
      </span>
      <span className={`text-lg font-semibold font-mono ${trendColor}`}>
        {value}
      </span>
    </div>
  );
}
