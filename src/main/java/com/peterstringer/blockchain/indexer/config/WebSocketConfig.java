package com.peterstringer.blockchain.indexer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configures WebSocket messaging with STOMP protocol support for real-time
 * dashboard updates.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   Browser ──► /ws (SockJS) ──► STOMP ──► /topic/* subscriptions
 *                                  ▲
 *                                  │
 *                          /app/* messages (client → server)
 * </pre>
 *
 * <h2>Topics</h2>
 * <ul>
 *   <li>{@code /topic/indexer/{chain}/progress} — periodic progress updates
 *       (block count, throughput, ETA).</li>
 *   <li>{@code /topic/indexer/{chain}/blocks} — notifications when individual
 *       blocks are indexed (last N blocks per batch).</li>
 *   <li>{@code /topic/indexer/{chain}/rpc-health} — RPC provider circuit
 *       breaker state changes.</li>
 *   <li>{@code /topic/indexer/status} — overall indexer status broadcasts.</li>
 * </ul>
 *
 * <h2>Connection</h2>
 * <p>Clients connect via SockJS fallback at {@code /ws}:
 * <pre>
 *   const socket = new SockJS('/ws');
 *   const stompClient = Stomp.over(socket);
 *   stompClient.connect({}, () =&gt; {
 *       stompClient.subscribe('/topic/indexer/ethereum/progress', msg =&gt; {
 *           console.log(JSON.parse(msg.body));
 *       });
 *   });
 * </pre>
 *
 * <p><b>CORS:</b> All origins are permitted for development. In production,
 * restrict {@code setAllowedOriginPatterns} to your domain(s).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Configures the STOMP message broker.
     *
     * <ul>
     *   <li>Simple in-memory broker on {@code /topic} for pub/sub.</li>
     *   <li>Application destination prefix {@code /app} for client-to-server
     *       messages (not currently used but reserved for future interactive
     *       features like on-demand re-indexing requests).</li>
     * </ul>
     *
     * @param registry the broker registry to configure
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Registers the STOMP WebSocket endpoint with SockJS fallback.
     *
     * <p>Clients connect to {@code /ws}. SockJS provides transparent fallback
     * to HTTP long-polling for browsers/proxies that don't support WebSockets.
     *
     * @param registry the endpoint registry to configure
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
