/** Format a large number with commas: 1234567 -> "1,234,567" */
export function formatNumber(n: number | null | undefined): string {
  if (n == null) return "0";
  return n.toLocaleString("en-US");
}

/** Format a block number with commas */
export function formatBlock(n: number | null | undefined): string {
  return formatNumber(n);
}

/** Format blocks per second: 12.345 -> "12.3 blocks/s" */
export function formatRate(bps: number | null | undefined): string {
  if (bps == null) return "0.0 blocks/s";
  return `${bps.toFixed(1)} blocks/s`;
}

/** Format gas in Gwei: 12.345678 -> "12.35 Gwei" */
export function formatGwei(gwei: number | null | undefined): string {
  if (gwei == null) return "N/A";
  return `${gwei.toFixed(2)} Gwei`;
}

/** Truncate a hex hash: 0xabcdef123456... -> 0xabcd...3456 */
export function truncateHash(hash: string, chars = 4): string {
  if (!hash) return "";
  if (hash.length <= chars * 2 + 2) return hash;
  return `${hash.slice(0, chars + 2)}...${hash.slice(-chars)}`;
}

/** Calculate progress percentage */
export function progressPercent(current: number | undefined, target: number | undefined): number {
  if (!target) return 0;
  return Math.min(100, ((current ?? 0) / target) * 100);
}

/** Get chain color class */
export function getChainColor(chain: string): string {
  const colors: Record<string, string> = {
    ethereum: "var(--color-chain-ethereum)",
    polygon: "var(--color-chain-polygon)",
    arbitrum: "var(--color-chain-arbitrum)",
  };
  return colors[chain.toLowerCase()] ?? "var(--color-accent-blue)";
}

/** Get chain display name */
export function getChainDisplayName(chain: string): string {
  const names: Record<string, string> = {
    ethereum: "Ethereum",
    polygon: "Polygon",
    arbitrum: "Arbitrum",
  };
  return names[chain.toLowerCase()] ?? chain;
}

/** Get short chain icon letter(s) for avatar */
export function getChainIcon(chain: string): string {
  const icons: Record<string, string> = {
    ethereum: "ETH",
    polygon: "POL",
    arbitrum: "ARB",
  };
  return icons[chain.toLowerCase()] ?? chain.slice(0, 3).toUpperCase();
}

/** Get expected block time in milliseconds for a chain */
export function getChainBlockTimeMs(chain: string): number {
  const times: Record<string, number> = {
    ethereum: 12_000,
    polygon: 2_000,
    arbitrum: 250,
  };
  return times[chain.toLowerCase()] ?? 12_000;
}

/** Format a duration in milliseconds to a human-readable string */
export function formatDuration(ms: number): string {
  if (ms < 1_000) return "<1s";
  const seconds = Math.floor(ms / 1_000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ${seconds % 60}s`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ${minutes % 60}m`;
  const days = Math.floor(hours / 24);
  return `${days}d ${hours % 24}h`;
}
