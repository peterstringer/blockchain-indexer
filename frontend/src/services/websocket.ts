import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import type {
  IndexerProgressMessage,
  BlockIndexedMessage,
  RpcHealthMessage,
} from "@/types";

export type WebSocketStatus = "connecting" | "connected" | "disconnected";

type ProgressHandler = (msg: IndexerProgressMessage) => void;
type BlockHandler = (msg: BlockIndexedMessage) => void;
type RpcHealthHandler = (msg: RpcHealthMessage) => void;
type StatusChangeHandler = (status: WebSocketStatus) => void;

interface Subscription {
  chain: string;
  onProgress?: ProgressHandler;
  onBlock?: BlockHandler;
  onRpcHealth?: RpcHealthHandler;
}

const RECONNECT_DELAY_MS = 3000;
const MAX_RECONNECT_DELAY_MS = 30000;

class WebSocketService {
  private client: Client | null = null;
  private subscriptions: Subscription[] = [];
  private statusListeners: StatusChangeHandler[] = [];
  private _status: WebSocketStatus = "disconnected";
  private reconnectAttempt = 0;
  private deactivating = false;

  get status(): WebSocketStatus {
    return this._status;
  }

  /** Connect to the WebSocket endpoint */
  connect(): void {
    if (this.client?.active || this.deactivating) return;

    this.setStatus("connecting");

    try {
      this.client = new Client({
        webSocketFactory: () => {
          try {
            return new SockJS("/ws");
          } catch (e) {
            console.warn("SockJS connection failed:", e);
            throw e;
          }
        },
        reconnectDelay: this.getReconnectDelay(),
        onConnect: () => {
          this.reconnectAttempt = 0;
          this.setStatus("connected");
          this.resubscribeAll();
        },
        onDisconnect: () => {
          this.setStatus("disconnected");
        },
        onStompError: (frame) => {
          console.warn("STOMP error:", frame.headers["message"]);
          this.setStatus("disconnected");
        },
        onWebSocketClose: () => {
          this.reconnectAttempt++;
          this.setStatus("disconnected");
        },
        onWebSocketError: () => {
          this.setStatus("disconnected");
        },
      });

      this.client.activate();
    } catch (e) {
      console.warn("WebSocket client creation failed:", e);
      this.setStatus("disconnected");
    }
  }

  /** Disconnect from WebSocket */
  async disconnect(): Promise<void> {
    if (!this.client) return;

    this.deactivating = true;
    try {
      await this.client.deactivate();
    } catch {
      // Ignore deactivation errors
    }
    this.client = null;
    this.deactivating = false;
    this.setStatus("disconnected");
  }

  /** Subscribe to updates for a specific chain */
  subscribe(sub: Subscription): () => void {
    this.subscriptions.push(sub);

    if (this.client?.connected) {
      this.subscribeOne(sub);
    }

    return () => {
      this.subscriptions = this.subscriptions.filter((s) => s !== sub);
    };
  }

  /** Listen for connection status changes */
  onStatusChange(handler: StatusChangeHandler): () => void {
    this.statusListeners.push(handler);
    handler(this._status);
    return () => {
      this.statusListeners = this.statusListeners.filter((h) => h !== handler);
    };
  }

  private setStatus(status: WebSocketStatus): void {
    this._status = status;
    this.statusListeners.forEach((h) => h(status));
  }

  private getReconnectDelay(): number {
    const delay = RECONNECT_DELAY_MS * Math.pow(2, this.reconnectAttempt);
    return Math.min(delay, MAX_RECONNECT_DELAY_MS);
  }

  private resubscribeAll(): void {
    this.subscriptions.forEach((sub) => this.subscribeOne(sub));
  }

  private subscribeOne(sub: Subscription): void {
    if (!this.client?.connected) return;

    const { chain, onProgress, onBlock, onRpcHealth } = sub;

    if (onProgress) {
      this.client.subscribe(
        `/topic/indexer/${chain}/progress`,
        (message) => {
          onProgress(JSON.parse(message.body) as IndexerProgressMessage);
        }
      );
    }

    if (onBlock) {
      this.client.subscribe(
        `/topic/indexer/${chain}/blocks`,
        (message) => {
          onBlock(JSON.parse(message.body) as BlockIndexedMessage);
        }
      );
    }

    if (onRpcHealth) {
      this.client.subscribe(
        `/topic/indexer/${chain}/rpc-health`,
        (message) => {
          onRpcHealth(JSON.parse(message.body) as RpcHealthMessage);
        }
      );
    }
  }
}

/** Singleton WebSocket service instance */
export const wsService = new WebSocketService();
