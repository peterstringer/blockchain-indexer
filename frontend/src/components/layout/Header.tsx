import {
  Blocks,
  Wifi,
  WifiOff,
  LayoutDashboard,
  BarChart3,
  Settings,
  Github,
} from "lucide-react";
import type { WebSocketStatus } from "@/services/websocket";

export type TabId = "dashboard" | "analytics" | "settings";

interface HeaderProps {
  wsStatus: WebSocketStatus;
  activeTab: TabId;
  onTabChange: (tab: TabId) => void;
}

const tabs: { id: TabId; label: string; icon: typeof LayoutDashboard }[] = [
  { id: "dashboard", label: "Dashboard", icon: LayoutDashboard },
  { id: "analytics", label: "Analytics", icon: BarChart3 },
  { id: "settings", label: "Settings", icon: Settings },
];

export function Header({ wsStatus, activeTab, onTabChange }: HeaderProps) {
  return (
    <header className="border-b border-border bg-bg-secondary/80 backdrop-blur-sm sticky top-0 z-50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        {/* Top row: title + connection status */}
        <div className="flex items-center justify-between h-14">
          <div className="flex items-center gap-3">
            <Blocks className="w-6 h-6 text-accent-purple" />
            <h1 className="text-base font-semibold text-text-primary">
              Blockchain Indexer
            </h1>
          </div>
          <div className="flex items-center gap-4">
            {wsStatus === "connected" ? (
              <span className="flex items-center gap-1.5 text-xs text-accent-green">
                <Wifi className="w-3.5 h-3.5" />
                Live
              </span>
            ) : wsStatus === "connecting" ? (
              <span className="flex items-center gap-1.5 text-xs text-accent-amber animate-pulse">
                <Wifi className="w-3.5 h-3.5" />
                Connecting...
              </span>
            ) : (
              <span className="flex items-center gap-1.5 text-xs text-text-muted">
                <WifiOff className="w-3.5 h-3.5" />
                Disconnected
              </span>
            )}
            <a
              href="https://github.com"
              target="_blank"
              rel="noopener noreferrer"
              className="text-text-muted hover:text-text-secondary transition-colors"
            >
              <Github className="w-4 h-4" />
            </a>
          </div>
        </div>

        {/* Navigation tabs */}
        <nav className="flex gap-1 -mb-px">
          {tabs.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              onClick={() => onTabChange(id)}
              className={`flex items-center gap-2 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
                activeTab === id
                  ? "border-accent-purple text-accent-purple"
                  : "border-transparent text-text-muted hover:text-text-secondary hover:border-border-light"
              }`}
            >
              <Icon className="w-4 h-4" />
              {label}
            </button>
          ))}
        </nav>
      </div>
    </header>
  );
}
