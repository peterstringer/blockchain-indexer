import { CardSkeleton } from "@/components/common/Skeleton";
import { ExportView } from "@/components/dashboard/ExportView";
import type { IndexerStatus } from "@/types";

interface ExportPageProps {
  status: IndexerStatus;
}

export function ExportPage({ status }: ExportPageProps) {
  return <ExportView status={status} />;
}

export function ExportPageSkeleton() {
  return (
    <div className="space-y-6">
      <CardSkeleton />
      <CardSkeleton />
      <CardSkeleton />
    </div>
  );
}
