export type BadgeStatus = "running" | "stopped" | "healthy" | "degraded" | "down" | "error" | "info" | "warning";

interface StatusBadgeProps {
  status: BadgeStatus;
  label?: string;
  size?: "sm" | "md";
}

const styles: Record<BadgeStatus, string> = {
  running: "bg-accent-green/20 text-accent-green border-accent-green/30",
  healthy: "bg-accent-green/20 text-accent-green border-accent-green/30",
  stopped: "bg-text-muted/20 text-text-secondary border-text-muted/30",
  info: "bg-accent-blue/20 text-accent-blue border-accent-blue/30",
  warning: "bg-accent-amber/20 text-accent-amber border-accent-amber/30",
  degraded: "bg-accent-amber/20 text-accent-amber border-accent-amber/30",
  error: "bg-accent-red/20 text-accent-red border-accent-red/30",
  down: "bg-accent-red/20 text-accent-red border-accent-red/30",
};

const defaultLabels: Record<BadgeStatus, string> = {
  running: "Running",
  healthy: "Healthy",
  stopped: "Stopped",
  info: "Info",
  warning: "Warning",
  degraded: "Degraded",
  error: "Error",
  down: "Down",
};

const dotColors: Record<BadgeStatus, string> = {
  running: "bg-accent-green animate-pulse",
  healthy: "bg-accent-green animate-pulse",
  info: "bg-accent-blue",
  warning: "bg-accent-amber",
  degraded: "bg-accent-amber",
  error: "bg-accent-red",
  down: "bg-accent-red",
  stopped: "bg-text-muted",
};

export function StatusBadge({ status, label, size = "md" }: StatusBadgeProps) {
  const sizeClasses = size === "sm"
    ? "px-2 py-0.5 text-[10px]"
    : "px-2.5 py-1 text-xs";

  return (
    <span
      className={`inline-flex items-center gap-1.5 font-medium rounded-full border ${styles[status]} ${sizeClasses}`}
    >
      <span className={`w-1.5 h-1.5 rounded-full ${dotColors[status]}`} />
      {label ?? defaultLabels[status]}
    </span>
  );
}
