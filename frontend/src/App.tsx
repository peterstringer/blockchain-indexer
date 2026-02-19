import { useState } from "react";
import { Layout } from "@/components/layout/Layout";
import type { TabId } from "@/components/layout/Header";
import { Dashboard } from "@/components/dashboard/Dashboard";
import { AnalyticsView } from "@/components/dashboard/AnalyticsView";
import { SettingsView } from "@/components/dashboard/SettingsView";
import { useWebSocketConnection } from "@/hooks/useWebSocket";
import { useIndexerStatus } from "@/hooks/useIndexerStatus";
import { Loader2 } from "lucide-react";

export default function App() {
  const wsStatus = useWebSocketConnection();
  const { data: status, isLoading, error } = useIndexerStatus();
  const [activeTab, setActiveTab] = useState<TabId>("dashboard");

  return (
    <Layout
      wsStatus={wsStatus}
      activeTab={activeTab}
      onTabChange={setActiveTab}
    >
      {isLoading ? (
        <div className="flex items-center justify-center h-64">
          <Loader2 className="w-6 h-6 text-text-muted animate-spin" />
        </div>
      ) : error ? (
        <div className="flex items-center justify-center h-64">
          <p className="text-accent-red text-sm">
            Failed to connect to backend: {error.message}
          </p>
        </div>
      ) : status ? (
        <>
          {activeTab === "dashboard" && <Dashboard status={status} />}
          {activeTab === "analytics" && <AnalyticsView status={status} />}
          {activeTab === "settings" && <SettingsView status={status} />}
        </>
      ) : null}
    </Layout>
  );
}
