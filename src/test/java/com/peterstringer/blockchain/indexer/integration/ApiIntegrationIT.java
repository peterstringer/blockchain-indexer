package com.peterstringer.blockchain.indexer.integration;

import com.peterstringer.blockchain.indexer.repository.CheckpointRepository;
import com.peterstringer.blockchain.indexer.service.BlockIndexerService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the REST API using {@link TestRestTemplate}.
 *
 * <p>Validates the complete API lifecycle against a real Spring context
 * with PostgreSQL, Flyway migrations, and demo-mode indexing.
 */
@DisplayName("API Integration")
@AutoConfigureTestRestTemplate
class ApiIntegrationIT extends AbstractIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BlockIndexerService indexerService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @AfterEach
    void tearDown() {
        indexerService.stopAll();
        checkpointRepository.deleteAll();
    }

    // =========================================================================
    // Health endpoint
    // =========================================================================

    @Nested
    @DisplayName("GET /api/indexer/health")
    class HealthEndpointTests {

        @Test
        @DisplayName("should return healthy status with demo mode enabled")
        void returnsHealthy() {
            ResponseEntity<Map<String, Object>> response = getJson("/api/indexer/health");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "healthy");
            assertThat(response.getBody()).containsEntry("demoMode", true);
            assertThat(response.getBody()).containsKey("timestamp");
        }
    }

    // =========================================================================
    // Status endpoints
    // =========================================================================

    @Nested
    @DisplayName("GET /api/indexer/status")
    class StatusEndpointTests {

        @Test
        @DisplayName("should return stopped status initially")
        void returnsStopped() {
            ResponseEntity<Map<String, Object>> response = getJson("/api/indexer/status");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("running", false);
            assertThat(response.getBody()).containsEntry("mode", "STOPPED");
        }

        @Test
        @DisplayName("should include all configured chains")
        @SuppressWarnings("unchecked")
        void includesAllChains() {
            ResponseEntity<Map<String, Object>> response = getJson("/api/indexer/status");

            Map<String, Object> chains = (Map<String, Object>) response.getBody().get("chains");
            assertThat(chains).containsKeys("ethereum", "polygon");
        }

        @Test
        @DisplayName("should return 404 for unknown chain status")
        void returns404ForUnknownChain() {
            ResponseEntity<Map<String, Object>> response = getJson("/api/indexer/status/unknown");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // =========================================================================
    // Full API lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Full API Lifecycle")
    class FullLifecycleTests {

        @Test
        @DisplayName("should execute complete indexing lifecycle via API")
        void completeLifecycle() {
            // 1. Verify initially stopped
            ResponseEntity<Map<String, Object>> status = getJson("/api/indexer/status");
            assertThat(status.getBody()).containsEntry("running", false);

            // 2. Start ethereum backfill
            ResponseEntity<Map<String, Object>> startResponse = postJson(
                    "/api/indexer/start",
                    """
                    {"chain": "ethereum", "mode": "BACKFILL"}
                    """);
            assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(startResponse.getBody()).containsEntry("status", "started");

            // 3. Wait until checkpoints show progress
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        ResponseEntity<List<Map<String, Object>>> cpResponse =
                                getJsonList("/api/indexer/checkpoints");
                        assertThat(cpResponse.getBody()).isNotEmpty();
                    });

            // 4. Stop indexing
            ResponseEntity<Map<String, Object>> stopResponse = postJson(
                    "/api/indexer/stop",
                    """
                    {"chain": "ethereum"}
                    """);
            assertThat(stopResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

            // 5. Verify checkpoints persisted
            ResponseEntity<List<Map<String, Object>>> checkpoints =
                    getJsonList("/api/indexer/checkpoints");
            assertThat(checkpoints.getBody()).isNotEmpty();
        }
    }

    // =========================================================================
    // Start/Stop edge cases
    // =========================================================================

    @Nested
    @DisplayName("Start/Stop Edge Cases")
    class StartStopEdgeCaseTests {

        @Test
        @DisplayName("should return 404 when starting unknown chain")
        void returns404ForUnknownChain() {
            ResponseEntity<Map<String, Object>> response = postJson(
                    "/api/indexer/start",
                    """
                    {"chain": "nonexistent", "mode": "BACKFILL"}
                    """);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("should return 409 when starting already-running chain")
        void returns409WhenAlreadyRunning() {
            // Start ethereum
            postJson("/api/indexer/start",
                    """
                    {"chain": "ethereum", "mode": "BACKFILL"}
                    """);

            // Try starting again
            ResponseEntity<Map<String, Object>> response = postJson(
                    "/api/indexer/start",
                    """
                    {"chain": "ethereum", "mode": "BACKFILL"}
                    """);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("should return 400 when missing mode")
        void returns400WhenMissingMode() {
            ResponseEntity<Map<String, Object>> response = postJson(
                    "/api/indexer/start",
                    """
                    {"chain": "ethereum"}
                    """);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should return 400 when missing chain")
        void returns400WhenMissingChain() {
            ResponseEntity<Map<String, Object>> response = postJson(
                    "/api/indexer/start",
                    """
                    {"mode": "BACKFILL"}
                    """);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should stop all chains when body is empty")
        void stopsAllWhenBodyEmpty() {
            // Start both chains
            postJson("/api/indexer/start", """
                    {"chain": "ethereum", "mode": "BACKFILL"}
                    """);
            postJson("/api/indexer/start", """
                    {"chain": "polygon", "mode": "BACKFILL"}
                    """);

            // Stop all with empty body
            ResponseEntity<Map<String, Object>> response = postJson("/api/indexer/stop", "{}");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("chain", "all");
        }
    }

    // =========================================================================
    // Checkpoint reset
    // =========================================================================

    @Nested
    @DisplayName("Checkpoint Reset")
    class CheckpointResetTests {

        @Test
        @DisplayName("should reset checkpoint with confirm=true")
        void resetsWithConfirmation() {
            // Start indexing, wait for some progress, then stop
            postJson("/api/indexer/start", """
                    {"chain": "ethereum", "mode": "BACKFILL"}
                    """);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        assertThat(checkpointRepository.findByChain("ethereum")).isPresent();
                    });

            indexerService.stopIndexing("ethereum");

            // Reset
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    "/api/indexer/checkpoints/ethereum/reset?confirm=true",
                    HttpMethod.POST, null, MAP_TYPE);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "reset");
            assertThat(checkpointRepository.findByChain("ethereum")).isEmpty();
        }

        @Test
        @DisplayName("should return 400 without confirm parameter")
        void returns400WithoutConfirm() {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    "/api/indexer/checkpoints/ethereum/reset",
                    HttpMethod.POST, null, MAP_TYPE);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should return 404 for unknown chain reset")
        void returns404ForUnknownChain() {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    "/api/indexer/checkpoints/unknown/reset?confirm=true",
                    HttpMethod.POST, null, MAP_TYPE);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // =========================================================================
    // Metrics endpoint
    // =========================================================================

    @Nested
    @DisplayName("GET /api/indexer/metrics")
    class MetricsEndpointTests {

        @Test
        @DisplayName("should return metrics in demo mode")
        @SuppressWarnings("unchecked")
        void returnsMetrics() {
            ResponseEntity<Map<String, Object>> response = getJson("/api/indexer/metrics");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("demoMode", true);
            assertThat(response.getBody()).containsKey("chains");
            assertThat(response.getBody()).containsKey("timestamp");

            Map<String, Object> chains = (Map<String, Object>) response.getBody().get("chains");
            assertThat(chains).containsKeys("ethereum", "polygon");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ResponseEntity<Map<String, Object>> getJson(String url) {
        return restTemplate.exchange(url, HttpMethod.GET, null, MAP_TYPE);
    }

    private ResponseEntity<List<Map<String, Object>>> getJsonList(String url) {
        return restTemplate.exchange(url, HttpMethod.GET, null, LIST_TYPE);
    }

    private ResponseEntity<Map<String, Object>> postJson(String url, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(json, headers);
        return restTemplate.exchange(url, HttpMethod.POST, entity, MAP_TYPE);
    }
}
