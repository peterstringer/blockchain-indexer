import { useEffect, useState, useCallback, useRef } from "react";
import { wsService, type WebSocketStatus } from "@/services/websocket";
import type {
  IndexerProgressMessage,
  BlockIndexedMessage,
  RpcHealthMessage,
} from "@/types";

const MAX_RECENT_BLOCKS = 50;

/** Hook to manage the WebSocket connection lifecycle */
export function useWebSocketConnection() {
  const [status, setStatus] = useState<WebSocketStatus>(wsService.status);

  useEffect(() => {
    const unsub = wsService.onStatusChange(setStatus);

    // Delay connect slightly to avoid StrictMode double-mount race
    const timer = setTimeout(() => {
      wsService.connect();
    }, 100);

    return () => {
      clearTimeout(timer);
      unsub();
      wsService.disconnect();
    };
  }, []);

  return status;
}

/** Hook to subscribe to real-time updates for a specific chain */
export function useChainUpdates(chain: string) {
  const [progress, setProgress] = useState<IndexerProgressMessage | null>(null);
  const [recentBlocks, setRecentBlocks] = useState<BlockIndexedMessage[]>([]);
  const [rpcHealth, setRpcHealth] = useState<RpcHealthMessage | null>(null);

  const onProgress = useCallback((msg: IndexerProgressMessage) => {
    setProgress(msg);
  }, []);

  const onBlock = useCallback((msg: BlockIndexedMessage) => {
    setRecentBlocks((prev) => {
      const next = [msg, ...prev];
      return next.slice(0, MAX_RECENT_BLOCKS);
    });
  }, []);

  const onRpcHealth = useCallback((msg: RpcHealthMessage) => {
    setRpcHealth(msg);
  }, []);

  useEffect(() => {
    const unsub = wsService.subscribe({
      chain,
      onProgress,
      onBlock,
      onRpcHealth,
    });
    return unsub;
  }, [chain, onProgress, onBlock, onRpcHealth]);

  return { progress, recentBlocks, rpcHealth };
}

/** Hook to collect progress messages over time for charting */
export function useProgressHistory(chain: string, maxPoints = 60) {
  const [history, setHistory] = useState<IndexerProgressMessage[]>([]);
  const historyRef = useRef(history);
  historyRef.current = history;

  useEffect(() => {
    const unsub = wsService.subscribe({
      chain,
      onProgress: (msg) => {
        setHistory((prev) => {
          const next = [...prev, msg];
          return next.slice(-maxPoints);
        });
      },
    });
    return unsub;
  }, [chain, maxPoints]);

  return history;
}
