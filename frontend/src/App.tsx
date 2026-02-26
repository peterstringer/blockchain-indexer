import { useState, useEffect, useCallback } from "react";
import { Layout } from "@/components/layout/Layout";
import type { TabId } from "@/components/layout/Header";
import { DashboardPage, DashboardPageSkeleton, DashboardPageError } from "@/pages/DashboardPage";
import { AnalyticsPage, AnalyticsPageSkeleton } from "@/pages/AnalyticsPage";
import { SettingsPage, SettingsPageSkeleton } from "@/pages/SettingsPage";
import { ExportPage, ExportPageSkeleton } from "@/pages/ExportPage";
import { useWebSocketConnection } from "@/hooks/useWebSocket";
import { useIndexerStatus, useStartIndexing, useStopIndexing } from "@/hooks/useIndexerStatus";

export default function App() {
  const wsStatus = useWebSocketConnection();
  const { data: status, isLoading, error, refetch } = useIndexerStatus();
  const [activeTab, setActiveTab] = useState<TabId>("dashboard");
  const startMutation = useStartIndexing();
  const stopMutation = useStopIndexing();

  // Keyboard shortcut: Space to toggle indexing
  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      // Don't trigger when typing in inputs
      if (
        e.target instanceof HTMLInputElement ||
        e.target instanceof HTMLTextAreaElement ||
        e.target instanceof HTMLButtonElement
      ) {
        return;
      }

      if (e.code === "Space" && status) {
        e.preventDefault();
        if (status.running) {
          stopMutation.mutate({});
        } else {
          const chainKeys = Object.keys(status.chains);
          for (const key of chainKeys) {
            startMutation.mutate({ chain: key, mode: "BACKFILL" });
          }
        }
      }

      // Tab switching: 1, 2, 3, 4
      if (e.key === "1") setActiveTab("dashboard");
      if (e.key === "2") setActiveTab("analytics");
      if (e.key === "3") setActiveTab("export");
      if (e.key === "4") setActiveTab("settings");
    },
    [status, startMutation, stopMutation]
  );

  useEffect(() => {
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [handleKeyDown]);

  // Update page title based on active tab and status
  useEffect(() => {
    const tabNames: Record<TabId, string> = {
      dashboard: "Dashboard",
      analytics: "Analytics",
      export: "Export",
      settings: "Settings",
    };
    const prefix = status?.running ? "● " : "";
    document.title = `${prefix}${tabNames[activeTab]} — Blockchain Indexer`;
  }, [activeTab, status?.running]);

  return (
    <Layout wsStatus={wsStatus} activeTab={activeTab} onTabChange={setActiveTab}>
      {isLoading ? (
        <>
          {activeTab === "dashboard" && <DashboardPageSkeleton />}
          {activeTab === "analytics" && <AnalyticsPageSkeleton />}
          {activeTab === "export" && <ExportPageSkeleton />}
          {activeTab === "settings" && <SettingsPageSkeleton />}
        </>
      ) : error ? (
        <DashboardPageError error={error} onRetry={() => refetch()} />
      ) : status ? (
        <>
          {activeTab === "dashboard" && <DashboardPage status={status} />}
          {activeTab === "analytics" && <AnalyticsPage status={status} />}
          {activeTab === "export" && <ExportPage status={status} />}
          {activeTab === "settings" && <SettingsPage status={status} />}
        </>
      ) : null}
    </Layout>
  );
}
