package com.peterstringer.blockchain.indexer.integration;

import com.peterstringer.blockchain.indexer.repository.CheckpointRepository;
import com.peterstringer.blockchain.indexer.service.BlockIndexerService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for WebSocket STOMP messaging.
 *
 * <p>Connects a real STOMP client to the embedded server, subscribes
 * to indexer topics, triggers indexing, and verifies that progress
 * and block-indexed messages are received.
 */
@DisplayName("WebSocket Integration")
class WebSocketIntegrationIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private BlockIndexerService indexerService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        List<Transport> transports = List.of(
                new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);

        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
    }

    @AfterEach
    void tearDown() {
        indexerService.stopAll();
        checkpointRepository.deleteAll();
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    // =========================================================================
    // Connection tests
    // =========================================================================

    @Nested
    @DisplayName("Connection")
    class ConnectionTests {

        @Test
        @DisplayName("should connect to WebSocket endpoint via SockJS")
        void connectsViaSockJS() throws Exception {
            String url = "ws://localhost:" + port + "/ws";

            CompletableFuture<StompSession> future = stompClient.connectAsync(
                    url, new StompSessionHandlerAdapter() {});

            StompSession session = future.get(10, TimeUnit.SECONDS);
            assertThat(session.isConnected()).isTrue();

            session.disconnect();
        }

        @Test
        @DisplayName("should reconnect after disconnection")
        void reconnectsAfterDisconnect() throws Exception {
            String url = "ws://localhost:" + port + "/ws";

            // First connection
            StompSession session1 = stompClient.connectAsync(
                    url, new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
            assertThat(session1.isConnected()).isTrue();
            session1.disconnect();

            // Second connection
            StompSession session2 = stompClient.connectAsync(
                    url, new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
            assertThat(session2.isConnected()).isTrue();
            session2.disconnect();
        }
    }

    // =========================================================================
    // Progress subscription tests
    // =========================================================================

    @Nested
    @DisplayName("Progress Subscription")
    class ProgressSubscriptionTests {

        @Test
        @DisplayName("should receive progress messages on ethereum topic")
        void receivesProgressMessages() throws Exception {
            String url = "ws://localhost:" + port + "/ws";
            StompSession session = stompClient.connectAsync(
                    url, new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);

            MapStompFrameHandler handler = new MapStompFrameHandler();
            session.subscribe("/topic/indexer/ethereum/progress", handler);

            // Allow subscription to register
            Thread.sleep(500);

            // Trigger indexing
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            // Wait for at least one progress message
            Awaitility.await()
                    .atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(300))
                    .untilAsserted(() -> {
                        assertThat(handler.messages).isNotEmpty();
                    });

            Map<String, Object> msg = handler.messages.peek();
            assertThat(msg).containsKey("chain");
            assertThat(msg).containsKey("currentBlock");
            assertThat(msg).containsKey("blocksProcessed");
            assertThat(msg).containsKey("timestamp");

            session.disconnect();
        }

        @Test
        @DisplayName("should receive block-indexed messages")
        void receivesBlockMessages() throws Exception {
            String url = "ws://localhost:" + port + "/ws";
            StompSession session = stompClient.connectAsync(
                    url, new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);

            MapStompFrameHandler handler = new MapStompFrameHandler();
            session.subscribe("/topic/indexer/ethereum/blocks", handler);

            Thread.sleep(500);

            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(300))
                    .untilAsserted(() -> {
                        assertThat(handler.messages).isNotEmpty();
                    });

            Map<String, Object> msg = handler.messages.peek();
            assertThat(msg).containsKey("chain");
            assertThat(msg).containsKey("blockNumber");
            assertThat(msg).containsKey("transactionCount");

            session.disconnect();
        }
    }

    // =========================================================================
    // Multiple subscriptions
    // =========================================================================

    @Nested
    @DisplayName("Multiple Subscriptions")
    class MultipleSubscriptionTests {

        @Test
        @DisplayName("should receive messages on multiple chain topics")
        void receivesFromMultipleChains() throws Exception {
            String url = "ws://localhost:" + port + "/ws";
            StompSession session = stompClient.connectAsync(
                    url, new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);

            MapStompFrameHandler ethHandler = new MapStompFrameHandler();
            MapStompFrameHandler polyHandler = new MapStompFrameHandler();

            session.subscribe("/topic/indexer/ethereum/progress", ethHandler);
            session.subscribe("/topic/indexer/polygon/progress", polyHandler);

            Thread.sleep(500);

            // Start both chains
            indexerService.startAll(BlockIndexerService.IndexMode.BACKFILL);

            // Wait for messages from both chains
            Awaitility.await()
                    .atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        assertThat(ethHandler.messages).isNotEmpty();
                        assertThat(polyHandler.messages).isNotEmpty();
                    });

            session.disconnect();
        }
    }

    // =========================================================================
    // Test STOMP frame handler
    // =========================================================================

    /**
     * STOMP frame handler that deserializes payloads as {@code Map<String, Object>}
     * and collects them into a thread-safe blocking queue for test assertions.
     */
    static class MapStompFrameHandler implements StompFrameHandler {

        final BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return Map.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders headers, Object payload) {
            messages.add((Map<String, Object>) payload);
        }
    }
}
