package com.peterstringer.blockchain.indexer.service;

import com.peterstringer.blockchain.indexer.model.IndexerStatus;
import com.peterstringer.blockchain.indexer.model.ws.BlockIndexedMessage;
import com.peterstringer.blockchain.indexer.model.ws.IndexerProgressMessage;
import com.peterstringer.blockchain.indexer.model.ws.RpcHealthMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for broadcasting real-time indexer updates to WebSocket subscribers.
 *
 * <p>Wraps {@link SimpMessagingTemplate} with typed methods for each message
 * category and built-in throttling to prevent flooding subscribers during
 * high-throughput backfill operations.
 *
 * <h2>Topic Hierarchy</h2>
 * <pre>
 *   /topic/indexer/
 *   ├── status                     ← overall indexer status (all chains)
 *   └── {chain}/
 *       ├── progress               ← periodic progress snapshots
 *       ├── blocks                 ← individual block notifications
 *       └── rpc-health             ← provider circuit breaker updates
 * </pre>
 *
 * <h2>Throttling</h2>
 * <p>Progress and block messages are throttled to a maximum of
 * {@value #MIN_SEND_INTERVAL_MS}ms between sends per chain per topic.
 * This prevents overwhelming the WebSocket connection during backfill
 * when hundreds of blocks per second may be processed. RPC health and
 * status broadcasts are not throttled since they occur infrequently.
 *
 * @see com.peterstringer.blockchain.indexer.config.WebSocketConfig
 */
@Service
public class WebSocketService {

    private static final Logger log = LoggerFactory.getLogger(WebSocketService.class);

    /** Minimum interval between progress messages per chain (500ms = max 2/second). */
    private static final long MIN_SEND_INTERVAL_MS = 500;

    private static final String TOPIC_PREFIX = "/topic/indexer/";

    private final SimpMessagingTemplate messagingTemplate;

    /** Tracks last send time per topic key (e.g. "ethereum:progress") for throttling. */
    private final Map<String, Long> lastSendTimes = new ConcurrentHashMap<>();

    public WebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Sends a progress update for a specific chain.
     *
     * <p>Destination: {@code /topic/indexer/{chain}/progress}
     *
     * <p>Throttled to at most 2 messages per second per chain. Updates
     * arriving faster than the throttle interval are silently dropped.
     *
     * @param chain the chain key (e.g. "ethereum")
     * @param msg   the progress snapshot
     */
    public void sendProgress(String chain, IndexerProgressMessage msg) {
        String topic = TOPIC_PREFIX + chain + "/progress";
        sendThrottled(chain + ":progress", topic, msg);
    }

    /**
     * Sends a block-indexed notification for a specific chain.
     *
     * <p>Destination: {@code /topic/indexer/{chain}/blocks}
     *
     * <p>Throttled to at most 2 messages per second per chain. During
     * backfill, only the most recent blocks in each batch should be sent
     * to avoid excessive message volume.
     *
     * @param chain the chain key (e.g. "ethereum")
     * @param msg   the block details
     */
    public void sendBlockIndexed(String chain, BlockIndexedMessage msg) {
        String topic = TOPIC_PREFIX + chain + "/blocks";
        sendThrottled(chain + ":blocks", topic, msg);
    }

    /**
     * Sends an RPC health snapshot for a specific chain.
     *
     * <p>Destination: {@code /topic/indexer/{chain}/rpc-health}
     *
     * <p>Not throttled — these messages are infrequent and only sent
     * when provider state changes are detected.
     *
     * @param chain the chain key (e.g. "ethereum")
     * @param msg   the provider health snapshot
     */
    public void sendRpcHealth(String chain, RpcHealthMessage msg) {
        String topic = TOPIC_PREFIX + chain + "/rpc-health";
        send(topic, msg);
    }

    /**
     * Broadcasts the overall indexer status across all chains.
     *
     * <p>Destination: {@code /topic/indexer/status}
     *
     * <p>Not throttled — intended for periodic status broadcasts
     * (e.g. after start/stop operations).
     *
     * @param status the full indexer status snapshot
     */
    public void broadcastStatus(IndexerStatus status) {
        send(TOPIC_PREFIX + "status", status);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Sends a message only if the minimum interval has elapsed since the
     * last send for the given throttle key.
     */
    private void sendThrottled(String throttleKey, String topic, Object payload) {
        long now = System.currentTimeMillis();
        Long lastSent = lastSendTimes.get(throttleKey);
        if (lastSent != null && (now - lastSent) < MIN_SEND_INTERVAL_MS) {
            return;
        }
        lastSendTimes.put(throttleKey, now);
        send(topic, payload);
    }

    /**
     * Sends a payload to the given STOMP destination, catching exceptions
     * to prevent WebSocket failures from disrupting the indexing pipeline.
     */
    private void send(String topic, Object payload) {
        try {
            messagingTemplate.convertAndSend(topic, payload);
        } catch (Exception e) {
            log.debug("Failed to send WebSocket message to {}: {}", topic, e.getMessage());
        }
    }
}
