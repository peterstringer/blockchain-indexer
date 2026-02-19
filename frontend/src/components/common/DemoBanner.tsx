import { FlaskConical } from "lucide-react";

export function DemoBanner() {
  return (
    <div className="bg-accent-amber/10 border border-accent-amber/20 rounded-lg px-4 py-2.5 flex items-center gap-3">
      <FlaskConical className="w-4 h-4 text-accent-amber shrink-0" />
      <p className="text-xs text-accent-amber">
        <span className="font-semibold">Demo Mode</span> — Displaying synthetic
        data for demonstration purposes. Connect real RPC providers to index
        live blockchain data.
      </p>
    </div>
  );
}
