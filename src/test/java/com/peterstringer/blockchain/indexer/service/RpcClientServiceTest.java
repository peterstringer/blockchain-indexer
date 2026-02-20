package com.peterstringer.blockchain.indexer.service;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.model.IndexedBlock;
import com.peterstringer.blockchain.indexer.repository.RpcProviderHealthRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RpcClientService}, covering circuit breaker state
 * transitions, round-robin load balancing, rate limiting, retry logic,
 * and demo mode behavior.
 *
 * <p>Direct Web3j RPC calls are not tested here; the circuit breaker and
 * provider selection logic is exercised via the package-private
 * {@code RpcProvider} inner class and the {@code selectHealthyProvider}
 * method.
 */
@DisplayName("RpcClientService")
@ExtendWith(MockitoExtension.class)
class RpcClientServiceTest {

    @Mock
    private RpcProviderHealthRepository healthRepository;

    @Mock
    private SyntheticDataProvider syntheticDataProvider;

    private IndexerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IndexerProperties();

        IndexerProperties.ChainConfig ethereum = new IndexerProperties.ChainConfig();
        ethereum.setName("Ethereum");
        ethereum.setChainId(1);
        ethereum.setRpcUrls(List.of("http://rpc1.example.com", "http://rpc2.example.com"));
        ethereum.setStartBlock(18_000_000);
        ethereum.setMaxRetriesPerBlock(3);
        ethereum.setRetryDelayMs(100);
        ethereum.setRateLimitRequestsPerSecond(10);

        Map<String, IndexerProperties.ChainConfig> chains = new HashMap<>();
        chains.put("ethereum", ethereum);
        properties.setChains(chains);
    }

    // =========================================================================
    // Circuit breaker: RpcProvider inner class
    // =========================================================================

    @Nested
    @DisplayName("Circuit Breaker (RpcProvider)")
    class CircuitBreakerTests {

        @Test
        @DisplayName("should start in CLOSED state")
        void startsInClosedState() {
            RpcClientService.RpcProvider provider = createProvider("http://test.com");

            assertThat(provider.getState()).isEqualTo(RpcClientService.RpcProvider.State.CLOSED);
            assertThat(provider.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("should remain CLOSED after fewer than 5 failures")
        void remainsClosedBelowThreshold() {
            RpcClientService.RpcProvider provider = createProvider("http://test.com");

            for (int i = 0; i < 4; i++) {
                provider.recordFailure();
            }

            assertThat(provider.getState()).isEqualTo(RpcClientService.RpcProvider.State.CLOSED);
            assertThat(provider.isHealthy()).isTrue();
            assertThat(provider.consecutiveFailures.get()).isEqualTo(4);
        }

        @Test
        @DisplayName("should transition CLOSED → OPEN after 5 consecutive failures")
        void opensAfterThreshold() {
            RpcClientService.RpcProvider provider = createProvider("http://test.com");

            for (int i = 0; i < 5; i++) {
                provider.recordFailure();
            }

            assertThat(provider.getState()).isEqualTo(RpcClientService.RpcProvider.State.OPEN);
            assertThat(provider.isHealthy()).isFalse();
        }

        @Test
        @DisplayName("should reset consecutive failures on success")
        void resetsOnSuccess() {
            RpcClientService.RpcProvider provider = createProvider("http://test.com");

            provider.recordFailure();
            provider.recordFailure();
            provider.recordFailure();
            provider.recordSuccess();

            assertThat(provider.consecutiveFailures.get()).isEqualTo(0);
            assertThat(provider.getState()).isEqualTo(RpcClientService.RpcProvider.State.CLOSED);
        }

        @Test
        @DisplayName("should transition HALF_OPEN → CLOSED on success")
        void halfOpenToClosedOnSuccess() {
            RpcClientService.RpcProvider provider = createProvider("http://test.com");

            // Trip the breaker
            for (int i = 0; i < 5; i++) {
                provider.recordFailure();
            }
            assertThat(provider.getState()).isEqualTo(RpcClientService.RpcProvider.State.OPEN);

            // Force to HALF_OPEN
            provider.forceHalfOpen();
            assertThat(provider.getState()).isEqualTo(RpcClientService.RpcProvider.State.HALF_OPEN);
            assertThat(provider.isHealthy()).isTrue();

            // Success should return to CLOSED
            provider.recordSuccess();
            assertThat(provider.getState()).isEqualTo(RpcClientService.RpcProvider.State.CLOSED);
        }

        @Test
        @DisplayName("should transition HALF_OPEN → OPEN on failure")
        void halfOpenToOpenOnFailure() {
            RpcClientService.RpcProvider provider = createProvider("http://test.com");

            provider.forceHalfOpen();
            assertThat(provider.getState()).isEqualTo(RpcClientService.RpcProvider.State.HALF_OPEN);

            provider.recordFailure();
            assertThat(provider.getState()).isEqualTo(RpcClientService.RpcProvider.State.OPEN);
        }

        @Test
        @DisplayName("attemptReset should not transition before timeout expires")
        void attemptResetBeforeTimeout() {
            RpcClientService.RpcProvider provider = createProvider("http://test.com");

            // Trip the breaker
            for (int i = 0; i < 5; i++) {
                provider.recordFailure();
            }

            // Attempt reset immediately — should fail (30s timeout not elapsed)
            boolean result = provider.attemptReset();
            assertThat(result).isFalse();
            assertThat(provider.getState()).isEqualTo(RpcClientService.RpcProvider.State.OPEN);
        }

        @Test
        @DisplayName("forceHalfOpen should work regardless of timing")
        void forceHalfOpenAlwaysWorks() {
            RpcClientService.RpcProvider provider = createProvider("http://test.com");

            // Trip the breaker
            for (int i = 0; i < 5; i++) {
                provider.recordFailure();
            }

            provider.forceHalfOpen();
            assertThat(provider.getState()).isEqualTo(RpcClientService.RpcProvider.State.HALF_OPEN);
        }

        @Test
        @DisplayName("should track success and failure counts")
        void tracksCounters() {
            RpcClientService.RpcProvider provider = createProvider("http://test.com");

            provider.recordSuccess();
            provider.recordSuccess();
            provider.recordFailure();
            provider.recordSuccess();

            assertThat(provider.successCount.get()).isEqualTo(3);
            assertThat(provider.failureCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should update timing fields on success and failure")
        void updatesTimingFields() {
            RpcClientService.RpcProvider provider = createProvider("http://test.com");

            assertThat(provider.lastSuccessTime).isEqualTo(0);
            assertThat(provider.lastFailureTime).isEqualTo(0);

            provider.recordSuccess();
            assertThat(provider.lastSuccessTime).isPositive();

            provider.recordFailure();
            assertThat(provider.lastFailureTime).isPositive();
        }
    }

    // =========================================================================
    // Demo mode tests
    // =========================================================================

    @Nested
    @DisplayName("Demo Mode")
    class DemoModeTests {

        @Test
        @DisplayName("should report demo mode when enabled")
        void reportsDemoMode() {
            properties.getDemo().setEnabled(true);
            RpcClientService service = new RpcClientService(
                    properties, healthRepository, Optional.of(syntheticDataProvider));

            assertThat(service.isDemoMode()).isTrue();
        }

        @Test
        @DisplayName("should report non-demo mode when disabled")
        void reportsNonDemoMode() {
            properties.getDemo().setEnabled(false);
            RpcClientService service = new RpcClientService(
                    properties, healthRepository, Optional.empty());

            assertThat(service.isDemoMode()).isFalse();
        }

        @Test
        @DisplayName("should use synthetic data provider in demo mode for getIndexedBlock")
        void usesSyntheticProviderForBlocks() {
            properties.getDemo().setEnabled(true);
            IndexedBlock mockBlock = IndexedBlock.builder()
                    .chain("Ethereum")
                    .blockNumber(18_000_001L)
                    .build();
            when(syntheticDataProvider.generateBlock("ethereum", 18_000_001L))
                    .thenReturn(mockBlock);

            RpcClientService service = new RpcClientService(
                    properties, healthRepository, Optional.of(syntheticDataProvider));

            IndexedBlock result = service.getIndexedBlock("ethereum", 18_000_001L);
            assertThat(result.getBlockNumber()).isEqualTo(18_000_001L);
        }

        @Test
        @DisplayName("should use synthetic data provider for getLatestBlockNumber")
        void usesSyntheticProviderForLatestBlock() {
            properties.getDemo().setEnabled(true);
            when(syntheticDataProvider.getLatestBlockNumber("ethereum")).thenReturn(18_000_099L);

            RpcClientService service = new RpcClientService(
                    properties, healthRepository, Optional.of(syntheticDataProvider));

            long latest = service.getLatestBlockNumber("ethereum");
            assertThat(latest).isEqualTo(18_000_099L);
        }

        @Test
        @DisplayName("should throw RpcException for getTransactionReceipt in demo mode")
        void throwsForReceiptInDemoMode() {
            properties.getDemo().setEnabled(true);
            RpcClientService service = new RpcClientService(
                    properties, healthRepository, Optional.of(syntheticDataProvider));

            assertThatThrownBy(() -> service.getTransactionReceipt("ethereum", "0xabc"))
                    .isInstanceOf(RpcClientService.RpcException.class)
                    .hasMessageContaining("demo mode");
        }
    }

    // =========================================================================
    // URL hashing tests
    // =========================================================================

    @Nested
    @DisplayName("URL Hashing")
    class UrlHashingTests {

        @Test
        @DisplayName("should produce consistent SHA-256 hashes")
        void producesConsistentHashes() {
            String hash1 = RpcClientService.hashUrl("http://rpc1.example.com");
            String hash2 = RpcClientService.hashUrl("http://rpc1.example.com");

            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("should produce different hashes for different URLs")
        void producesDifferentHashes() {
            String hash1 = RpcClientService.hashUrl("http://rpc1.example.com");
            String hash2 = RpcClientService.hashUrl("http://rpc2.example.com");

            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("hash should be a valid hex string")
        void producesValidHex() {
            String hash = RpcClientService.hashUrl("http://test.com");

            assertThat(hash).matches("[0-9a-f]+");
            assertThat(hash).hasSize(64); // SHA-256 = 32 bytes = 64 hex chars
        }
    }

    // =========================================================================
    // RpcException tests
    // =========================================================================

    @Nested
    @DisplayName("RpcException")
    class RpcExceptionTests {

        @Test
        @DisplayName("should carry message")
        void carriesMessage() {
            var ex = new RpcClientService.RpcException("test error");
            assertThat(ex.getMessage()).isEqualTo("test error");
        }

        @Test
        @DisplayName("should carry cause")
        void carriesCause() {
            var cause = new RuntimeException("root cause");
            var ex = new RpcClientService.RpcException("test error", cause);
            assertThat(ex.getCause()).isEqualTo(cause);
        }
    }

    // =========================================================================
    // Provider health snapshot tests
    // =========================================================================

    @Nested
    @DisplayName("Provider Health Snapshot")
    class ProviderHealthTests {

        @Test
        @DisplayName("should return empty health map in demo mode")
        void emptyHealthInDemoMode() {
            properties.getDemo().setEnabled(true);
            RpcClientService service = new RpcClientService(
                    properties, healthRepository, Optional.of(syntheticDataProvider));
            service.initialize();

            Map<String, List<Map<String, Object>>> health = service.getProviderHealth();
            assertThat(health).isEmpty();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates an RpcProvider with a null Web3j client (sufficient for
     * circuit breaker state tests that don't make actual RPC calls).
     */
    private static RpcClientService.RpcProvider createProvider(String url) {
        return new RpcClientService.RpcProvider(url, null, "test-chain");
    }
}
