import { CardSkeleton } from "@/components/common/Skeleton";
import { SettingsView } from "@/components/dashboard/SettingsView";
import type { IndexerStatus } from "@/types";

interface SettingsPageProps {
  status: IndexerStatus;
}

export function SettingsPage({ status }: SettingsPageProps) {
  return <SettingsView status={status} />;
}

export function SettingsPageSkeleton() {
  return (
    <div className="space-y-6">
      <CardSkeleton />
      <CardSkeleton />
      <CardSkeleton />
    </div>
  );
}
