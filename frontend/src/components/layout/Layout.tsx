import type { ReactNode } from "react";
import { Header, type TabId } from "./Header";
import { Footer } from "./Footer";
import type { WebSocketStatus } from "@/services/websocket";

interface LayoutProps {
  children: ReactNode;
  wsStatus: WebSocketStatus;
  activeTab: TabId;
  onTabChange: (tab: TabId) => void;
}

export function Layout({ children, wsStatus, activeTab, onTabChange }: LayoutProps) {
  return (
    <div className="min-h-screen bg-bg-primary flex flex-col">
      <Header wsStatus={wsStatus} activeTab={activeTab} onTabChange={onTabChange} />
      <main className="max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-6 flex-1">
        {children}
      </main>
      <Footer />
    </div>
  );
}
