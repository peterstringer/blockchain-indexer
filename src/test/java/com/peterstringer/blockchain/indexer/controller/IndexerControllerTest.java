package com.peterstringer.blockchain.indexer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.dto.StartIndexingRequest;
import com.peterstringer.blockchain.indexer.dto.StopIndexingRequest;
import com.peterstringer.blockchain.indexer.model.IndexerCheckpoint;
import com.peterstringer.blockchain.indexer.model.IndexerStatus;
import com.peterstringer.blockchain.indexer.repository.CheckpointRepository;
import com.peterstringer.blockchain.indexer.repository.MetricsRepository;
import com.peterstringer.blockchain.indexer.service.BlockIndexerService;
import com.peterstringer.blockchain.indexer.service.RpcClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer tests for {@link IndexerController} using {@code @WebMvcTest}.
 * All service dependencies are mocked, validating HTTP status codes, JSON
 * response shapes, request validation, and error handling.
 */
@DisplayName("IndexerController")
@WebMvcTest(IndexerController.class)
class IndexerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private BlockIndexerService indexerService;

    @MockitoBean
    private CheckpointRepository checkpointRepository;

    @MockitoBean
    private MetricsRepository metricsRepository;

    @MockitoBean
    private IndexerProperties properties;

    @MockitoBean
    private RpcClientService rpcClientService;

    @BeforeEach
    void setUp() {
        // Default chain config for validation
        IndexerProperties.ChainConfig ethereum = new IndexerProperties.ChainConfig();
        ethereum.setName("Ethereum");
        ethereum.setChainId(1);
        ethereum.setRpcUrls(List.of("http://localhost:8545"));
        ethereum.setStartBlock(18_000_000);

        Map<String, IndexerProperties.ChainConfig> chains = new HashMap<>();
        chains.put("ethereum", ethereum);
        when(properties.getChains()).thenReturn(chains);
    }

    // =========================================================================
    // POST /api/indexer/start
    // =========================================================================

    @Nested
    @DisplayName("POST /api/indexer/start")
    class StartEndpointTests {

        @Test
        @DisplayName("should start indexing with valid request")
        void startsWithValidRequest() throws Exception {
            doNothing().when(indexerService)
                    .startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            StartIndexingRequest request = new StartIndexingRequest(
                    "ethereum", BlockIndexerService.IndexMode.BACKFILL);

            mockMvc.perform(post("/api/indexer/start")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("started")))
                    .andExpect(jsonPath("$.chain", is("ethereum")))
                    .andExpect(jsonPath("$.mode", is("BACKFILL")));

            verify(indexerService).startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);
        }

        @Test
        @DisplayName("should accept INCREMENTAL mode")
        void acceptsIncrementalMode() throws Exception {
            doNothing().when(indexerService)
                    .startIndexing("ethereum", BlockIndexerService.IndexMode.INCREMENTAL);

            StartIndexingRequest request = new StartIndexingRequest(
                    "ethereum", BlockIndexerService.IndexMode.INCREMENTAL);

            mockMvc.perform(post("/api/indexer/start")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode", is("INCREMENTAL")));
        }

        @Test
        @DisplayName("should return 400 when chain is missing")
        void returns400WhenChainMissing() throws Exception {
            mockMvc.perform(post("/api/indexer/start")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mode\": \"BACKFILL\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when mode is missing")
        void returns400WhenModeMissing() throws Exception {
            mockMvc.perform(post("/api/indexer/start")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chain\": \"ethereum\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when chain is blank")
        void returns400WhenChainBlank() throws Exception {
            mockMvc.perform(post("/api/indexer/start")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"chain\": \"\", \"mode\": \"BACKFILL\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 for unknown chain")
        void returns404ForUnknownChain() throws Exception {
            doThrow(new IllegalArgumentException("Unknown chain: unknown"))
                    .when(indexerService).startIndexing("unknown", BlockIndexerService.IndexMode.BACKFILL);

            StartIndexingRequest request = new StartIndexingRequest(
                    "unknown", BlockIndexerService.IndexMode.BACKFILL);

            mockMvc.perform(post("/api/indexer/start")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)));
        }

        @Test
        @DisplayName("should return 409 when chain is already running")
        void returns409WhenAlreadyRunning() throws Exception {
            doThrow(new IllegalStateException("Chain ethereum is already running"))
                    .when(indexerService).startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            StartIndexingRequest request = new StartIndexingRequest(
                    "ethereum", BlockIndexerService.IndexMode.BACKFILL);

            mockMvc.perform(post("/api/indexer/start")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)));
        }
    }

    // =========================================================================
    // POST /api/indexer/stop
    // =========================================================================

    @Nested
    @DisplayName("POST /api/indexer/stop")
    class StopEndpointTests {

        @Test
        @DisplayName("should stop specific chain")
        void stopsSpecificChain() throws Exception {
            StopIndexingRequest request = new StopIndexingRequest("ethereum");

            mockMvc.perform(post("/api/indexer/stop")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("stopped")))
                    .andExpect(jsonPath("$.chain", is("ethereum")));

            verify(indexerService).stopIndexing("ethereum");
        }

        @Test
        @DisplayName("should stop all chains when body is empty")
        void stopsAllWhenEmpty() throws Exception {
            mockMvc.perform(post("/api/indexer/stop")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.chain", is("all")));

            verify(indexerService).stopAll();
        }

        @Test
        @DisplayName("should stop all chains when no body")
        void stopsAllWhenNoBody() throws Exception {
            mockMvc.perform(post("/api/indexer/stop")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.chain", is("all")));

            verify(indexerService).stopAll();
        }

        @Test
        @DisplayName("should return 404 for unknown chain on stop")
        void returns404ForUnknownChain() throws Exception {
            StopIndexingRequest request = new StopIndexingRequest("unknown");

            mockMvc.perform(post("/api/indexer/stop")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // =========================================================================
    // GET /api/indexer/status
    // =========================================================================

    @Nested
    @DisplayName("GET /api/indexer/status")
    class StatusEndpointTests {

        @Test
        @DisplayName("should return full indexer status")
        void returnsFullStatus() throws Exception {
            IndexerStatus status = IndexerStatus.builder()
                    .running(true)
                    .mode(IndexerStatus.Mode.BACKFILL)
                    .chains(Map.of("ethereum", IndexerStatus.ChainStatus.builder()
                            .lastBlock(18_000_500L)
                            .blocksIndexed(500L)
                            .transactionsIndexed(50_000L)
                            .blocksPerSecond(42.5)
                            .rpcHealth("RUNNING_BACKFILL")
                            .build()))
                    .build();

            when(indexerService.getStatus()).thenReturn(status);

            mockMvc.perform(get("/api/indexer/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.running", is(true)))
                    .andExpect(jsonPath("$.mode", is("BACKFILL")))
                    .andExpect(jsonPath("$.chains.ethereum.lastBlock", is(18000500)))
                    .andExpect(jsonPath("$.chains.ethereum.blocksIndexed", is(500)))
                    .andExpect(jsonPath("$.chains.ethereum.blocksPerSecond", is(42.5)));
        }

        @Test
        @DisplayName("should return stopped status when not running")
        void returnsStoppedStatus() throws Exception {
            IndexerStatus status = IndexerStatus.builder()
                    .running(false)
                    .mode(IndexerStatus.Mode.STOPPED)
                    .chains(Collections.emptyMap())
                    .build();

            when(indexerService.getStatus()).thenReturn(status);

            mockMvc.perform(get("/api/indexer/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.running", is(false)))
                    .andExpect(jsonPath("$.mode", is("STOPPED")));
        }
    }

    // =========================================================================
    // GET /api/indexer/status/{chain}
    // =========================================================================

    @Nested
    @DisplayName("GET /api/indexer/status/{chain}")
    class ChainStatusEndpointTests {

        @Test
        @DisplayName("should return status for specific chain")
        void returnsChainStatus() throws Exception {
            IndexerStatus status = IndexerStatus.builder()
                    .running(true)
                    .mode(IndexerStatus.Mode.BACKFILL)
                    .chains(Map.of("ethereum", IndexerStatus.ChainStatus.builder()
                            .lastBlock(18_000_500L)
                            .blocksIndexed(500L)
                            .transactionsIndexed(50_000L)
                            .blocksPerSecond(42.5)
                            .rpcHealth("RUNNING_BACKFILL")
                            .build()))
                    .build();

            when(indexerService.getStatus()).thenReturn(status);

            mockMvc.perform(get("/api/indexer/status/ethereum"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastBlock", is(18000500)))
                    .andExpect(jsonPath("$.blocksPerSecond", is(42.5)));
        }

        @Test
        @DisplayName("should return 404 for unknown chain")
        void returns404ForUnknownChain() throws Exception {
            mockMvc.perform(get("/api/indexer/status/unknown"))
                    .andExpect(status().isNotFound());
        }
    }

    // =========================================================================
    // GET /api/indexer/health
    // =========================================================================

    @Nested
    @DisplayName("GET /api/indexer/health")
    class HealthEndpointTests {

        @Test
        @DisplayName("should return health status")
        void returnsHealthStatus() throws Exception {
            when(rpcClientService.isDemoMode()).thenReturn(true);
            when(indexerService.getStatus()).thenReturn(IndexerStatus.builder()
                    .running(false)
                    .mode(IndexerStatus.Mode.STOPPED)
                    .chains(Collections.emptyMap())
                    .build());

            mockMvc.perform(get("/api/indexer/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("healthy")))
                    .andExpect(jsonPath("$.demoMode", is(true)))
                    .andExpect(jsonPath("$.running", is(false)))
                    .andExpect(jsonPath("$.chainsConfigured", is(1)))
                    .andExpect(jsonPath("$.timestamp").isNumber());
        }
    }

    // =========================================================================
    // GET /api/indexer/checkpoints
    // =========================================================================

    @Nested
    @DisplayName("GET /api/indexer/checkpoints")
    class CheckpointEndpointTests {

        @Test
        @DisplayName("should return all checkpoints")
        void returnsAllCheckpoints() throws Exception {
            IndexerCheckpoint cp = new IndexerCheckpoint();
            cp.setId(1L);
            cp.setChain("ethereum");
            cp.setLastIndexedBlock(18_000_500L);
            cp.setTotalBlocksIndexed(500L);
            cp.setTotalTransactionsIndexed(50_000L);
            cp.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            cp.setLastUpdated(OffsetDateTime.now(ZoneOffset.UTC));

            when(checkpointRepository.findAllOrderByLastIndexedBlockDesc())
                    .thenReturn(List.of(cp));

            mockMvc.perform(get("/api/indexer/checkpoints"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].chain", is("ethereum")))
                    .andExpect(jsonPath("$[0].lastIndexedBlock", is(18000500)));
        }

        @Test
        @DisplayName("should return empty list when no checkpoints exist")
        void returnsEmptyList() throws Exception {
            when(checkpointRepository.findAllOrderByLastIndexedBlockDesc())
                    .thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/indexer/checkpoints"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // =========================================================================
    // POST /api/indexer/checkpoints/{chain}/reset
    // =========================================================================

    @Nested
    @DisplayName("POST /api/indexer/checkpoints/{chain}/reset")
    class ResetCheckpointEndpointTests {

        @Test
        @DisplayName("should reset checkpoint with confirm=true")
        void resetsWithConfirmation() throws Exception {
            when(indexerService.getStatus()).thenReturn(IndexerStatus.builder()
                    .running(false)
                    .mode(IndexerStatus.Mode.STOPPED)
                    .chains(Map.of("ethereum", IndexerStatus.ChainStatus.builder()
                            .rpcHealth("STOPPED")
                            .lastBlock(0L)
                            .blocksIndexed(0L)
                            .transactionsIndexed(0L)
                            .blocksPerSecond(0.0)
                            .build()))
                    .build());
            when(checkpointRepository.findByChain("ethereum"))
                    .thenReturn(Optional.of(new IndexerCheckpoint()));

            mockMvc.perform(post("/api/indexer/checkpoints/ethereum/reset")
                            .param("confirm", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("reset")))
                    .andExpect(jsonPath("$.chain", is("ethereum")))
                    .andExpect(jsonPath("$.newStartBlock", is(18000000)));

            verify(checkpointRepository).delete(any(IndexerCheckpoint.class));
        }

        @Test
        @DisplayName("should return 400 without confirm parameter")
        void returns400WithoutConfirm() throws Exception {
            mockMvc.perform(post("/api/indexer/checkpoints/ethereum/reset"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists());

            verify(checkpointRepository, never()).delete(any());
        }

        @Test
        @DisplayName("should return 404 for unknown chain")
        void returns404ForUnknownChain() throws Exception {
            mockMvc.perform(post("/api/indexer/checkpoints/unknown/reset")
                            .param("confirm", "true"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when chain is actively running")
        void returns409WhenRunning() throws Exception {
            when(indexerService.getStatus()).thenReturn(IndexerStatus.builder()
                    .running(true)
                    .mode(IndexerStatus.Mode.BACKFILL)
                    .chains(Map.of("ethereum", IndexerStatus.ChainStatus.builder()
                            .rpcHealth("RUNNING_BACKFILL")
                            .lastBlock(18_000_500L)
                            .blocksIndexed(500L)
                            .transactionsIndexed(50_000L)
                            .blocksPerSecond(42.5)
                            .build()))
                    .build());

            mockMvc.perform(post("/api/indexer/checkpoints/ethereum/reset")
                            .param("confirm", "true"))
                    .andExpect(status().isConflict());
        }
    }

    // =========================================================================
    // GET /api/indexer/metrics
    // =========================================================================

    @Nested
    @DisplayName("GET /api/indexer/metrics")
    class MetricsEndpointTests {

        @Test
        @DisplayName("should return metrics in demo mode")
        void returnsMetricsInDemoMode() throws Exception {
            when(rpcClientService.isDemoMode()).thenReturn(true);
            when(indexerService.getStatus()).thenReturn(IndexerStatus.builder()
                    .running(false)
                    .mode(IndexerStatus.Mode.STOPPED)
                    .chains(Map.of("ethereum", IndexerStatus.ChainStatus.builder()
                            .lastBlock(0L)
                            .blocksIndexed(0L)
                            .transactionsIndexed(0L)
                            .blocksPerSecond(0.0)
                            .rpcHealth("NOT_STARTED")
                            .build()))
                    .build());
            when(metricsRepository.findByChainAndMetricNameAndRecordedAtAfterOrderByRecordedAtAsc(
                    any(), any(), any())).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/indexer/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.demoMode", is(true)))
                    .andExpect(jsonPath("$.timestamp").isNumber())
                    .andExpect(jsonPath("$.chains.ethereum").exists());
        }

        @Test
        @DisplayName("should include RPC provider health in non-demo mode")
        void includesProviderHealthInNonDemoMode() throws Exception {
            when(rpcClientService.isDemoMode()).thenReturn(false);
            when(rpcClientService.getProviderHealth()).thenReturn(Collections.emptyMap());
            when(indexerService.getStatus()).thenReturn(IndexerStatus.builder()
                    .running(false)
                    .mode(IndexerStatus.Mode.STOPPED)
                    .chains(Collections.emptyMap())
                    .build());

            mockMvc.perform(get("/api/indexer/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.demoMode", is(false)))
                    .andExpect(jsonPath("$.rpcProviders").exists());
        }
    }
}
