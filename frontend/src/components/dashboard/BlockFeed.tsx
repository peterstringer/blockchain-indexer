import { useState, useEffect, useRef } from "react";
import { formatDistanceToNow } from "date-fns";
import { ChevronDown, ChevronUp, Fuel, Hash, Clock } from "lucide-react";
import { Card, CardHeader } from "@/components/common/Card";
import type { BlockIndexedMessage } from "@/types";
import {
  formatBlock,
  formatNumber,
  truncateHash,
  formatGwei,
  getChainDisplayName,
  getChainColor,
} from "@/utils/format";

interface BlockFeedProps {
  blocks: BlockIndexedMessage[];
}

export function BlockFeed({ blocks }: BlockFeedProps) {
  const [expandedBlock, setExpandedBlock] = useState<string | null>(null);
  const [autoScroll, setAutoScroll] = useState(true);
  const feedRef = useRef<HTMLDivElement>(null);
  const prevLengthRef = useRef(blocks.length);

  // Auto-scroll when new blocks arrive
  useEffect(() => {
    if (autoScroll && blocks.length > prevLengthRef.current && feedRef.current) {
      feedRef.current.scrollTop = 0;
    }
    prevLengthRef.current = blocks.length;
  }, [blocks.length, autoScroll]);

  const toggleExpand = (key: string) => {
    setExpandedBlock((prev) => (prev === key ? null : key));
  };

  const displayed = blocks.slice(0, 20);

  return (
    <Card>
      <CardHeader
        title="Block Feed"
        subtitle="Real-time indexed blocks"
        action={
          <button
            onClick={() => setAutoScroll(!autoScroll)}
            className={`text-[11px] px-2 py-1 rounded-md border transition-colors ${
              autoScroll
                ? "bg-accent-green/10 text-accent-green border-accent-green/20"
                : "bg-bg-primary text-text-muted border-border"
            }`}
          >
            {autoScroll ? "Auto-scroll ON" : "Auto-scroll OFF"}
          </button>
        }
      />
      <div ref={feedRef} className="max-h-[480px] overflow-y-auto space-y-1">
        {displayed.length === 0 ? (
          <div className="text-center text-text-muted text-xs py-12">
            Waiting for blocks...
          </div>
        ) : (
          displayed.map((block, i) => {
            const key = `${block.chain}-${block.blockNumber}-${i}`;
            const isExpanded = expandedBlock === key;
            const color = getChainColor(block.chain);

            return (
              <div
                key={key}
                className="border border-border/50 rounded-lg hover:border-border-light transition-colors"
              >
                {/* Main row */}
                <button
                  onClick={() => toggleExpand(key)}
                  className="w-full flex items-center gap-3 px-3 py-2.5 text-left"
                >
                  {/* Chain indicator */}
                  <div
                    className="w-1 h-8 rounded-full shrink-0"
                    style={{ backgroundColor: color }}
                  />

                  {/* Block info */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-semibold text-text-primary font-mono">
                        #{formatBlock(block.blockNumber)}
                      </span>
                      <span className="text-[11px] text-text-muted">
                        {getChainDisplayName(block.chain)}
                      </span>
                    </div>
                    <div className="flex items-center gap-3 mt-0.5 text-[11px] text-text-muted">
                      <span>{block.transactionCount} txns</span>
                      <span>{formatGwei(block.baseFeeGwei)} base</span>
                      <span>
                        {formatDistanceToNow(new Date(block.timestamp), {
                          addSuffix: true,
                        })}
                      </span>
                    </div>
                  </div>

                  {/* Expand icon */}
                  {isExpanded ? (
                    <ChevronUp className="w-4 h-4 text-text-muted shrink-0" />
                  ) : (
                    <ChevronDown className="w-4 h-4 text-text-muted shrink-0" />
                  )}
                </button>

                {/* Expanded details */}
                {isExpanded && (
                  <div className="px-3 pb-3 pt-1 border-t border-border/30 space-y-2 text-xs">
                    <div className="grid grid-cols-2 gap-2">
                      <DetailRow
                        icon={<Hash className="w-3 h-3" />}
                        label="Block Hash"
                        value={truncateHash(block.blockHash, 10)}
                        mono
                      />
                      <DetailRow
                        icon={<Hash className="w-3 h-3" />}
                        label="Transactions"
                        value={String(block.transactionCount)}
                      />
                      <DetailRow
                        icon={<Fuel className="w-3 h-3" />}
                        label="Gas Used"
                        value={formatNumber(block.gasUsed)}
                      />
                      <DetailRow
                        icon={<Fuel className="w-3 h-3" />}
                        label="Base Fee"
                        value={formatGwei(block.baseFeeGwei)}
                      />
                      <DetailRow
                        icon={<Clock className="w-3 h-3" />}
                        label="Timestamp"
                        value={new Date(block.timestamp).toLocaleString()}
                      />
                    </div>
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>
    </Card>
  );
}

function DetailRow({
  icon,
  label,
  value,
  mono = false,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="flex items-start gap-1.5">
      <span className="text-text-muted mt-0.5">{icon}</span>
      <div>
        <span className="text-text-muted">{label}</span>
        <p className={`text-text-secondary ${mono ? "font-mono" : ""}`}>{value}</p>
      </div>
    </div>
  );
}
