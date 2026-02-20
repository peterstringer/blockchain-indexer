package com.peterstringer.blockchain.indexer.integration;

import com.peterstringer.blockchain.indexer.model.IndexedBlock;
import com.peterstringer.blockchain.indexer.model.IndexerCheckpoint;
import com.peterstringer.blockchain.indexer.repository.CheckpointRepository;
import com.peterstringer.blockchain.indexer.service.BlockIndexerService;
import com.peterstringer.blockchain.indexer.service.RpcClientService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests against a real local Ethereum node (Anvil/Foundry).
 *
 * <p>These tests are disabled by default and only run when the environment
 * variable {@code RPC_INTEGRATION_TESTS=true} is set. This avoids requiring
 * Docker images for Foundry in CI environments that don't support it.
 *
 * <p>To run: {@code RPC_INTEGRATION_TESTS=true mvn verify}
 */
@DisplayName("RPC Integration (Anvil)")
@EnabledIfEnvironmentVariable(named = "RPC_INTEGRATION_TESTS", matches = "true")
class RpcIntegrationIT extends AbstractIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> anvil =
            new GenericContainer<>("ghcr.io/foundry-rs/foundry:latest")
                    .withCommand("anvil", "--host", "0.0.0.0", "--port", "8545",
                            "--block-time", "1", "--accounts", "10")
                    .withExposedPorts(8545)
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofSeconds(30)));

    @DynamicPropertySource
    static void configureAnvil(DynamicPropertyRegistry registry) {
        registry.add("indexer.demo.enabled", () -> "false");
        registry.add("indexer.chains.ethereum.rpc-urls[0]",
                () -> "http://" + anvil.getHost() + ":" + anvil.getMappedPort(8545));
        registry.add("indexer.chains.ethereum.start-block", () -> "0");
        registry.add("indexer.chains.ethereum.end-block", () -> "5");
        // Remove polygon to simplify — only test ethereum against Anvil
        registry.add("indexer.chains.polygon.rpc-urls[0]",
                () -> "http://" + anvil.getHost() + ":" + anvil.getMappedPort(8545));
    }

    @Autowired
    private RpcClientService rpcClientService;

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
    // Real RPC tests
    // =========================================================================

    @Nested
    @DisplayName("Real RPC Calls")
    class RealRpcTests {

        @Test
        @DisplayName("should not be in demo mode")
        void notInDemoMode() {
            assertThat(rpcClientService.isDemoMode()).isFalse();
        }

        @Test
        @DisplayName("should fetch block from Anvil")
        void fetchesBlockFromAnvil() {
            IndexedBlock block = rpcClientService.getIndexedBlock("ethereum", 0L);

            assertThat(block).isNotNull();
            assertThat(block.getBlockNumber()).isEqualTo(0L);
            assertThat(block.getBlockHash()).isNotNull();
            assertThat(block.getBlockHash()).startsWith("0x");
        }

        @Test
        @DisplayName("should get latest block number from Anvil")
        void getsLatestBlockNumber() {
            long latest = rpcClientService.getLatestBlockNumber("ethereum");

            // Anvil with --block-time 1 produces blocks continuously
            assertThat(latest).isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("should complete backfill against real RPC")
        void completesBackfillAgainstRealRpc() {
            indexerService.startIndexing("ethereum", BlockIndexerService.IndexMode.BACKFILL);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum")
                                .orElse(null);
                        assertThat(cp).isNotNull();
                        assertThat(cp.getLastIndexedBlock()).isGreaterThanOrEqualTo(5L);
                    });

            IndexerCheckpoint cp = checkpointRepository.findByChain("ethereum").orElseThrow();
            assertThat(cp.getTotalBlocksIndexed()).isGreaterThan(0L);
        }
    }
}
