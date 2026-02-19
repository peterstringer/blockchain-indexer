import { formatDistanceToNow } from "date-fns";
import { Card, CardHeader } from "@/components/common/Card";
import type { BlockIndexedMessage } from "@/types";
import {
  formatBlock,
  formatNumber,
  truncateHash,
  formatGwei,
  getChainDisplayName,
} from "@/utils/format";

interface RecentBlocksProps {
  blocks: BlockIndexedMessage[];
}

export function RecentBlocks({ blocks }: RecentBlocksProps) {
  return (
    <Card>
      <CardHeader
        title="Recent Blocks"
        subtitle="Live feed of indexed blocks"
      />
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-text-muted border-b border-border">
              <th className="text-left pb-2 font-medium">Chain</th>
              <th className="text-right pb-2 font-medium">Block</th>
              <th className="text-left pb-2 font-medium pl-4">Hash</th>
              <th className="text-right pb-2 font-medium">Txns</th>
              <th className="text-right pb-2 font-medium">Gas Used</th>
              <th className="text-right pb-2 font-medium">Base Fee</th>
              <th className="text-right pb-2 font-medium">Age</th>
            </tr>
          </thead>
          <tbody>
            {blocks.length === 0 ? (
              <tr>
                <td
                  colSpan={7}
                  className="text-center text-text-muted py-8"
                >
                  Waiting for blocks...
                </td>
              </tr>
            ) : (
              blocks.slice(0, 15).map((block, i) => (
                <tr
                  key={`${block.chain}-${block.blockNumber}-${i}`}
                  className="border-b border-border/50 hover:bg-bg-card-hover transition-colors"
                >
                  <td className="py-2 text-text-secondary">
                    {getChainDisplayName(block.chain)}
                  </td>
                  <td className="py-2 text-right font-mono text-text-primary">
                    {formatBlock(block.blockNumber)}
                  </td>
                  <td className="py-2 pl-4 font-mono text-text-muted">
                    {truncateHash(block.blockHash, 6)}
                  </td>
                  <td className="py-2 text-right text-text-secondary">
                    {block.transactionCount}
                  </td>
                  <td className="py-2 text-right font-mono text-text-secondary">
                    {formatNumber(block.gasUsed)}
                  </td>
                  <td className="py-2 text-right font-mono text-text-secondary">
                    {formatGwei(block.baseFeeGwei)}
                  </td>
                  <td className="py-2 text-right text-text-muted">
                    {formatDistanceToNow(new Date(block.timestamp), {
                      addSuffix: true,
                    })}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </Card>
  );
}
