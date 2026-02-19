import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { Dashboard } from "@/components/dashboard/Dashboard";
import { DemoBanner } from "@/components/common/DemoBanner";
import { CardSkeleton, OverviewBarSkeleton } from "@/components/common/Skeleton";
import { fetchHealth } from "@/services/api";
import type { IndexerStatus } from "@/types";

interface DashboardPageProps {
  status: IndexerStatus;
}

export function DashboardPage({ status }: DashboardPageProps) {
  const { data: health } = useQuery({
    queryKey: ["health"],
    queryFn: fetchHealth,
    refetchInterval: 10000,
  });

  return (
    <div className="space-y-4">
      {health?.demoMode && <DemoBanner />}
      <Dashboard status={status} />
    </div>
  );
}

export function DashboardPageSkeleton() {
  return (
    <div className="space-y-6">
      <OverviewBarSkeleton />
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        <CardSkeleton />
        <CardSkeleton />
        <CardSkeleton />
      </div>
    </div>
  );
}

export function DashboardPageError({
  error,
  onRetry,
}: {
  error: Error;
  onRetry: () => void;
}) {
  return (
    <div className="flex flex-col items-center justify-center h-64 gap-4">
      <p className="text-accent-red text-sm">
        Failed to connect to backend: {error.message}
      </p>
      <button
        onClick={onRetry}
        className="flex items-center gap-2 px-4 py-2 text-sm font-medium rounded-lg bg-accent-blue/10 text-accent-blue border border-accent-blue/20 hover:bg-accent-blue/20 transition-colors"
      >
        <RefreshCw className="w-4 h-4" />
        Retry
      </button>
    </div>
  );
}
