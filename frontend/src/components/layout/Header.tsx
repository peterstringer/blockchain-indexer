import { useState } from "react";
import {
  Blocks,
  WifiOff,
  Loader2,
  LayoutDashboard,
  BarChart3,
  Settings,
  Github,
  Menu,
  X,
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

function WsIndicator({ status }: { status: WebSocketStatus }) {
  if (status === "connected") {
    return (
      <span className="flex items-center gap-1.5 text-xs text-accent-green">
        <span className="relative flex h-2 w-2">
          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent-green opacity-75" />
          <span className="relative inline-flex rounded-full h-2 w-2 bg-accent-green" />
        </span>
        Live
      </span>
    );
  }
  if (status === "connecting") {
    return (
      <span className="flex items-center gap-1.5 text-xs text-accent-amber">
        <Loader2 className="w-3.5 h-3.5 animate-spin" />
        Reconnecting...
      </span>
    );
  }
  return (
    <span className="flex items-center gap-1.5 text-xs text-text-muted">
      <WifiOff className="w-3.5 h-3.5" />
      Offline
    </span>
  );
}

export function Header({ wsStatus, activeTab, onTabChange }: HeaderProps) {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  const handleTabChange = (tab: TabId) => {
    onTabChange(tab);
    setMobileMenuOpen(false);
  };

  return (
    <header className="border-b border-border bg-bg-secondary/80 backdrop-blur-sm sticky top-0 z-50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        {/* Top row */}
        <div className="flex items-center justify-between h-14">
          <div className="flex items-center gap-3">
            <Blocks className="w-6 h-6 text-accent-purple" />
            <h1 className="text-base font-semibold text-text-primary">
              Blockchain Indexer
            </h1>
          </div>
          <div className="flex items-center gap-4">
            <WsIndicator status={wsStatus} />
            <a
              href="https://github.com"
              target="_blank"
              rel="noopener noreferrer"
              className="hidden sm:block text-text-muted hover:text-text-secondary transition-colors"
            >
              <Github className="w-4 h-4" />
            </a>
            {/* Mobile menu toggle */}
            <button
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
              className="sm:hidden text-text-muted hover:text-text-secondary transition-colors"
            >
              {mobileMenuOpen ? (
                <X className="w-5 h-5" />
              ) : (
                <Menu className="w-5 h-5" />
              )}
            </button>
          </div>
        </div>

        {/* Desktop navigation tabs */}
        <nav className="hidden sm:flex gap-1 -mb-px">
          {tabs.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              onClick={() => handleTabChange(id)}
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

      {/* Mobile navigation menu */}
      {mobileMenuOpen && (
        <div className="sm:hidden border-t border-border bg-bg-secondary animate-in slide-in-from-top duration-200">
          <nav className="flex flex-col px-4 py-2">
            {tabs.map(({ id, label, icon: Icon }) => (
              <button
                key={id}
                onClick={() => handleTabChange(id)}
                className={`flex items-center gap-3 px-3 py-3 text-sm font-medium rounded-lg transition-colors ${
                  activeTab === id
                    ? "bg-accent-purple/10 text-accent-purple"
                    : "text-text-muted hover:bg-bg-primary/50 hover:text-text-secondary"
                }`}
              >
                <Icon className="w-4 h-4" />
                {label}
              </button>
            ))}
          </nav>
        </div>
      )}
    </header>
  );
}
