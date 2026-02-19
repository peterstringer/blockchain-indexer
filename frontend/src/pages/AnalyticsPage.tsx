import { useQuery } from "@tanstack/react-query";
import { DemoBanner } from "@/components/common/DemoBanner";
import { ChartSkeleton } from "@/components/common/Skeleton";
import { AnalyticsView } from "@/components/dashboard/AnalyticsView";
import { fetchHealth } from "@/services/api";
import type { IndexerStatus } from "@/types";

interface AnalyticsPageProps {
  status: IndexerStatus;
}

export function AnalyticsPage({ status }: AnalyticsPageProps) {
  const { data: health } = useQuery({
    queryKey: ["health"],
    queryFn: fetchHealth,
    refetchInterval: 10000,
  });

  return (
    <div className="space-y-4">
      {health?.demoMode && <DemoBanner />}
      <AnalyticsView status={status} />
    </div>
  );
}

export function AnalyticsPageSkeleton() {
  return (
    <div className="space-y-6">
      <ChartSkeleton />
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <ChartSkeleton />
        <ChartSkeleton />
      </div>
    </div>
  );
}
