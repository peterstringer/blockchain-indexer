package com.peterstringer.blockchain.indexer.service;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.model.RpcProviderHealth;
import com.peterstringer.blockchain.indexer.repository.RpcProviderHealthRepository;
import com.peterstringer.blockchain.indexer.model.IndexedBlock;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service managing Web3j RPC connections to all configured EVM chains.
 *
 * <h2>Circuit Breaker</h2>
 * <p>Each RPC provider URL is wrapped in an {@link RpcProvider} that implements
 * a three-state circuit breaker:
 * <pre>
 *   CLOSED ──(5 consecutive failures)──► OPEN
 *     ▲                                    │
 *     │                               (30s timeout)
 *     │                                    ▼
 *     └──────(success)───── HALF_OPEN ◄────┘
 *                           │
 *                     (failure) ──► OPEN
 * </pre>
 * <ul>
 *   <li><b>CLOSED</b> — healthy; all requests are forwarded.</li>
 *   <li><b>OPEN</b> — tripped; requests are routed to other providers.</li>
 *   <li><b>HALF_OPEN</b> — a single probe request is sent after the reset
 *       timeout. Success returns to CLOSED; failure returns to OPEN.</li>
 * </ul>
 *
 * <h2>Rate Limiting</h2>
 * <p>A {@link Semaphore}-based token bucket limits requests per chain to the
 * configured {@code rateLimitRequestsPerSecond}. Permits are refilled by a
 * scheduled task every second. Callers block for up to 5 seconds before
 * a rate-limit timeout is raised.
 *
 * <h2>Provider Selection (Ordered Fallback)</h2>
 * <p>Providers are tried in configuration order: the first URL is the
 * primary, and subsequent URLs are fallbacks used only when all
 * higher-priority providers have their circuit breakers OPEN.
 * If all providers are unhealthy, the least-recently-failed
 * provider is promoted to HALF_OPEN for a recovery probe.
 *
 * <h2>Thread Safety</h2>
 * <p>All mutable state uses {@link AtomicInteger}, {@link ConcurrentHashMap},
 * or is guarded by {@code synchronized} blocks. The service is safe to call
 * from multiple indexer worker threads concurrently.
 */
@Service
public class RpcClientService {

    private static final Logger log = LoggerFactory.getLogger(RpcClientService.class);

    private static final int FAILURE_THRESHOLD = 5;
    private static final long RESET_TIMEOUT_MS = 30_000;
    private static final long RATE_LIMIT_WAIT_SECONDS = 5;

    private final IndexerProperties properties;
    private final RpcProviderHealthRepository healthRepository;
    private final SyntheticDataProvider syntheticDataProvider;
    private final boolean demoMode;

    private final ConcurrentHashMap<String, List<RpcProvider>> providersByChain = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> rateLimiters = new ConcurrentHashMap<>();

    public RpcClientService(IndexerProperties properties,
                            RpcProviderHealthRepository healthRepository,
                            java.util.Optional<SyntheticDataProvider> syntheticDataProvider) {
        this.properties = properties;
        this.healthRepository = healthRepository;
        this.syntheticDataProvider = syntheticDataProvider.orElse(null);
        this.demoMode = properties.getDemo().isEnabled();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Initializes Web3j clients for every configured chain and RPC URL.
     */
    @PostConstruct
    void initialize() {
        if (demoMode) {
            log.info("RpcClientService initialized in DEMO MODE — no RPC connections will be made");
            log.info("Synthetic data provider: seed={}, blocks={}",
                    properties.getDemo().getSeed(),
                    properties.getDemo().getSyntheticBlockCount());
            return;
        }

        properties.getChains().forEach((chainKey, chainConfig) -> {
            List<RpcProvider> providers = new ArrayList<>();
            for (String url : chainConfig.getRpcUrls()) {
                Web3j client = Web3j.build(new HttpService(url));
                providers.add(new RpcProvider(url, client, chainKey));
                log.info("Initialized RPC provider for chain={} url_hash={}",
                        chainKey, hashUrl(url));
            }
            providersByChain.put(chainKey, Collections.unmodifiableList(providers));
            rateLimiters.put(chainKey, new Semaphore(chainConfig.getRateLimitRequestsPerSecond()));
            log.info("Chain {} ready: {} provider(s), rate_limit={} req/s",
                    chainKey, providers.size(), chainConfig.getRateLimitRequestsPerSecond());
        });
        log.info("RpcClientService initialized for {} chain(s)", providersByChain.size());
    }

    /**
     * Shuts down all Web3j clients gracefully.
     */
    @PreDestroy
    void shutdown() {
        providersByChain.forEach((chain, providers) -> {
            for (RpcProvider provider : providers) {
                try {
                    provider.client.shutdown();
                    log.debug("Shut down RPC client for chain={} url_hash={}",
                            chain, provider.urlHash);
                } catch (Exception e) {
                    log.warn("Error shutting down RPC client for chain={} url_hash={}: {}",
                            chain, provider.urlHash, e.getMessage());
                }
            }
        });
        log.info("RpcClientService shut down — all clients closed");
    }

    // =========================================================================
    // Demo mode methods
    // =========================================================================

    /**
     * Returns {@code true} if demo mode is active and synthetic data will be
     * used instead of real RPC calls.
     */
    public boolean isDemoMode() {
        return demoMode;
    }

    /**
     * Returns a fully populated {@link IndexedBlock} with embedded transactions.
     *
     * <p>In demo mode, generates synthetic data via {@link SyntheticDataProvider}.
     * In production mode, fetches the block via RPC and converts it.
     *
     * @param chain       the chain identifier
     * @param blockNumber the block number
     * @return the indexed block with transactions
     */
    public IndexedBlock getIndexedBlock(String chain, long blockNumber) {
        if (demoMode) {
            return syntheticDataProvider.generateBlock(chain, blockNumber);
        }
        EthBlock.Block raw = getBlockByNumber(chain, blockNumber);
        IndexerProperties.ChainConfig config = getChainConfig(chain);
        return IndexedBlock.fromWeb3jBlock(chain, config.getChainId(), raw);
    }

    /**
     * Returns multiple {@link IndexedBlock}s concurrently.
     *
     * <p>In demo mode, generates synthetic data without any I/O.
     * In production mode, delegates to {@link #getBlocksAsync}.
     *
     * @param chain        the chain identifier
     * @param blockNumbers the block numbers to fetch
     * @param executor     the executor for async work
     * @return a future completing with all successfully fetched/generated blocks
     */
    public CompletableFuture<List<IndexedBlock>> getIndexedBlocksAsync(String chain,
                                                                       List<Long> blockNumbers,
                                                                       Executor executor) {
        if (demoMode) {
            return CompletableFuture.supplyAsync(() ->
                    blockNumbers.stream()
                            .map(num -> syntheticDataProvider.generateBlock(chain, num))
                            .toList(),
                    executor);
        }
        IndexerProperties.ChainConfig config = getChainConfig(chain);
        return getBlocksAsync(chain, blockNumbers, executor)
                .thenApply(blocks -> blocks.stream()
                        .map(raw -> {
                            try {
                                return IndexedBlock.fromWeb3jBlock(chain, config.getChainId(), raw);
                            } catch (Exception e) {
                                log.error("Failed to convert block {} on chain={}: {}",
                                        raw.getNumber(), chain, e.getMessage());
                                return null;
                            }
                        })
                        .filter(b -> b != null)
                        .toList());
    }

    // =========================================================================
    // Core RPC methods
    // =========================================================================

    /**
     * Fetches a single block by number, including full transaction objects.
     *
     * <p>Selects a healthy provider via round-robin, applies rate limiting,
     * and retries with exponential backoff on transient failures.
     *
     * @param chain       the chain identifier (e.g. "ethereum")
     * @param blockNumber the block number to fetch
     * @return the full block with transactions
     * @throws RpcException if all retries are exhausted
     */
    public EthBlock.Block getBlockByNumber(String chain, long blockNumber) {
        IndexerProperties.ChainConfig config = getChainConfig(chain);
        int maxRetries = config.getMaxRetriesPerBlock();
        long retryDelay = config.getRetryDelayMs();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            RpcProvider provider = selectHealthyProvider(chain);
            acquireRatePermit(chain);
            try {
                EthBlock response = provider.client.ethGetBlockByNumber(
                        new DefaultBlockParameterNumber(blockNumber), true
                ).send();
                if (response.hasError()) {
                    throw new IOException("RPC error: " + response.getError().getMessage());
                }
                EthBlock.Block block = response.getBlock();
                if (block == null) {
                    throw new IOException("Block " + blockNumber + " not found on " + chain);
                }
                provider.recordSuccess();
                log.debug("Fetched block {} from chain={} via url_hash={}", blockNumber, chain, provider.urlHash);
                return block;
            } catch (org.web3j.exceptions.MessageDecodingException e) {
                // Data format error from RPC provider — not transient, don't retry
                provider.recordFailure();
                log.warn("Non-retryable decoding error for block {} on chain={} via url_hash={}: {}",
                        blockNumber, chain, provider.urlHash, e.getMessage());
                throw new RpcException("Decoding error for block %d on %s: %s"
                        .formatted(blockNumber, chain, e.getMessage()), e);
            } catch (Exception e) {
                provider.recordFailure();
                log.warn("Attempt {}/{} failed for block {} on chain={} via url_hash={}: {}",
                        attempt, maxRetries, blockNumber, chain, provider.urlHash, e.getMessage());
                if (attempt < maxRetries) {
                    sleep(retryDelay * (1L << (attempt - 1)));
                }
            }
        }
        throw new RpcException("Failed to fetch block %d on %s after %d attempts"
                .formatted(blockNumber, chain, maxRetries));
    }

    /**
     * Fetches multiple blocks concurrently using the provided executor.
     *
     * @param chain        the chain identifier
     * @param blockNumbers the block numbers to fetch
     * @param executor     the executor to run async tasks on
     * @return a future that completes with all successfully fetched blocks
     */
    public CompletableFuture<List<EthBlock.Block>> getBlocksAsync(String chain,
                                                                   List<Long> blockNumbers,
                                                                   Executor executor) {
        List<CompletableFuture<EthBlock.Block>> futures = blockNumbers.stream()
                .map(num -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return getBlockByNumber(chain, num);
                    } catch (Exception e) {
                        log.error("Failed to fetch block {} on chain={}: {}", num, chain, e.getMessage());
                        return null;
                    }
                }, executor))
                .toList();

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(_ -> futures.stream()
                        .map(CompletableFuture::join)
                        .filter(block -> block != null)
                        .toList());
    }

    /**
     * Returns the latest block number (chain head) for the given chain.
     *
     * @param chain the chain identifier
     * @return the current head block number
     * @throws RpcException if the call fails
     */
    public long getLatestBlockNumber(String chain) {
        if (demoMode) {
            return syntheticDataProvider.getLatestBlockNumber(chain);
        }
        RpcProvider provider = selectHealthyProvider(chain);
        acquireRatePermit(chain);
        try {
            var response = provider.client.ethBlockNumber().send();
            if (response.hasError()) {
                throw new IOException("RPC error: " + response.getError().getMessage());
            }
            provider.recordSuccess();
            long blockNumber = response.getBlockNumber().longValueExact();
            log.debug("Latest block on chain={}: {}", chain, blockNumber);
            return blockNumber;
        } catch (Exception e) {
            provider.recordFailure();
            throw new RpcException("Failed to get latest block number on %s: %s"
                    .formatted(chain, e.getMessage()), e);
        }
    }

    /**
     * Fetches the transaction receipt for a given transaction hash.
     *
     * @param chain  the chain identifier
     * @param txHash the transaction hash
     * @return the receipt
     * @throws RpcException if the call fails or the receipt is not found
     */
    public TransactionReceipt getTransactionReceipt(String chain, String txHash) {
        if (demoMode) {
            throw new RpcException("Transaction receipts are not available in demo mode — "
                    + "use getIndexedBlock() which includes pre-built transaction data");
        }
        RpcProvider provider = selectHealthyProvider(chain);
        acquireRatePermit(chain);
        try {
            var response = provider.client.ethGetTransactionReceipt(txHash).send();
            if (response.hasError()) {
                throw new IOException("RPC error: " + response.getError().getMessage());
            }
            TransactionReceipt receipt = response.getTransactionReceipt()
                    .orElseThrow(() -> new IOException("Receipt not found for tx " + txHash));
            provider.recordSuccess();
            return receipt;
        } catch (Exception e) {
            provider.recordFailure();
            throw new RpcException("Failed to get receipt for tx %s on %s: %s"
                    .formatted(txHash, chain, e.getMessage()), e);
        }
    }

    // =========================================================================
    // Health monitoring
    // =========================================================================

    /**
     * Returns a snapshot of provider health across all chains.
     *
     * @return chain → list of provider status maps
     */
    public Map<String, List<Map<String, Object>>> getProviderHealth() {
        Map<String, List<Map<String, Object>>> health = new LinkedHashMap<>();
        providersByChain.forEach((chain, providers) -> {
            List<Map<String, Object>> providerStats = new ArrayList<>();
            for (RpcProvider p : providers) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("urlHash", p.urlHash);
                entry.put("state", p.getState().name());
                entry.put("successCount", p.successCount.get());
                entry.put("failureCount", p.failureCount.get());
                entry.put("consecutiveFailures", p.consecutiveFailures.get());
                entry.put("lastFailureTime", p.lastFailureTime);
                providerStats.add(entry);
            }
            health.put(chain, providerStats);
        });
        return health;
    }

    /**
     * Persists the in-memory circuit breaker state to the database every 30 seconds.
     */
    @Scheduled(fixedRate = 30_000)
    void persistProviderHealth() {
        providersByChain.forEach((chain, providers) -> {
            for (RpcProvider p : providers) {
                try {
                    RpcProviderHealth entity = healthRepository
                            .findByChainAndProviderUrlHash(chain, p.urlHash)
                            .orElseGet(() -> {
                                var h = new RpcProviderHealth();
                                h.setChain(chain);
                                h.setProviderUrlHash(p.urlHash);
                                return h;
                            });
                    entity.setState(toEntityState(p.getState()));
                    entity.setSuccessCount((long) p.successCount.get());
                    entity.setFailureCount((long) p.failureCount.get());
                    if (p.lastFailureTime > 0) {
                        entity.setLastFailureTime(
                                OffsetDateTime.ofInstant(Instant.ofEpochMilli(p.lastFailureTime), ZoneOffset.UTC));
                    }
                    if (p.lastSuccessTime > 0) {
                        entity.setLastSuccessTime(
                                OffsetDateTime.ofInstant(Instant.ofEpochMilli(p.lastSuccessTime), ZoneOffset.UTC));
                    }
                    entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    healthRepository.save(entity);
                } catch (Exception e) {
                    log.warn("Failed to persist health for chain={} url_hash={}: {}",
                            chain, p.urlHash, e.getMessage());
                }
            }
        });
        log.debug("Persisted provider health to database");
    }

    /**
     * Refills rate-limiter permits every second to maintain the configured
     * requests-per-second ceiling.
     */
    @Scheduled(fixedRate = 1_000)
    void refillRateLimitPermits() {
        properties.getChains().forEach((chainKey, config) -> {
            Semaphore semaphore = rateLimiters.get(chainKey);
            if (semaphore != null) {
                int available = semaphore.availablePermits();
                int limit = config.getRateLimitRequestsPerSecond();
                int toRelease = limit - available;
                if (toRelease > 0) {
                    semaphore.release(toRelease);
                }
            }
        });
    }

    // =========================================================================
    // Provider selection & rate limiting
    // =========================================================================

    /**
     * Selects a healthy provider for the given chain using ordered fallback.
     * The first configured URL is the primary; subsequent URLs are only used
     * when higher-priority providers have their circuit breakers OPEN.
     * If all providers are unhealthy, attempts to reset the least-recently-failed one.
     */
    RpcProvider selectHealthyProvider(String chain) {
        List<RpcProvider> providers = providersByChain.get(chain);
        if (providers == null || providers.isEmpty()) {
            throw new RpcException("No providers configured for chain: " + chain);
        }

        // Try providers in configuration order (primary first, then fallbacks)
        for (RpcProvider provider : providers) {
            if (provider.isHealthy()) {
                return provider;
            }
        }

        // All unhealthy — pick the one whose reset timeout expired most recently
        // and attempt a HALF_OPEN probe
        RpcProvider candidate = null;
        for (RpcProvider p : providers) {
            if (p.attemptReset()) {
                log.info("All providers unhealthy for chain={}, promoting url_hash={} to HALF_OPEN",
                        chain, p.urlHash);
                return p;
            }
            if (candidate == null || p.lastFailureTime < candidate.lastFailureTime) {
                candidate = p;
            }
        }

        // Last resort: force the oldest-failed provider
        if (candidate != null) {
            log.warn("Forcing HALF_OPEN on url_hash={} for chain={} — no healthy providers",
                    candidate.urlHash, chain);
            candidate.forceHalfOpen();
            return candidate;
        }

        throw new RpcException("No providers available for chain: " + chain);
    }

    private void acquireRatePermit(String chain) {
        Semaphore semaphore = rateLimiters.get(chain);
        if (semaphore == null) {
            return;
        }
        try {
            if (!semaphore.tryAcquire(RATE_LIMIT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Rate limit exceeded for chain={}, waited {}s", chain, RATE_LIMIT_WAIT_SECONDS);
                throw new RpcException("Rate limit exceeded for chain: " + chain);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException("Interrupted while waiting for rate limit permit on " + chain, e);
        }
    }

    // =========================================================================
    // Inner class: RpcProvider with circuit breaker
    // =========================================================================

    /**
     * Wraps a single Web3j client with circuit-breaker state.
     *
     * <p>Thread-safe: all counters use {@link AtomicInteger} and state
     * transitions are performed under a {@code synchronized} guard.
     */
    static class RpcProvider {

        enum State { CLOSED, OPEN, HALF_OPEN }

        final String url;
        final String urlHash;
        final Web3j client;
        final String chain;

        private volatile State state = State.CLOSED;
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        volatile long lastFailureTime = 0;
        volatile long lastSuccessTime = 0;

        RpcProvider(String url, Web3j client, String chain) {
            this.url = url;
            this.urlHash = hashUrl(url);
            this.client = client;
            this.chain = chain;
        }

        /**
         * Records a successful RPC call. Resets consecutive failures and
         * transitions HALF_OPEN → CLOSED.
         */
        synchronized void recordSuccess() {
            successCount.incrementAndGet();
            consecutiveFailures.set(0);
            lastSuccessTime = System.currentTimeMillis();
            if (state == State.HALF_OPEN) {
                log.info("Provider recovered: chain={} url_hash={} — HALF_OPEN → CLOSED", chain, urlHash);
                state = State.CLOSED;
            }
        }

        /**
         * Records a failed RPC call. Increments consecutive failures and
         * trips the circuit breaker if the threshold is reached.
         */
        synchronized void recordFailure() {
            failureCount.incrementAndGet();
            int failures = consecutiveFailures.incrementAndGet();
            lastFailureTime = System.currentTimeMillis();

            if (state == State.HALF_OPEN) {
                log.info("Probe failed: chain={} url_hash={} — HALF_OPEN → OPEN", chain, urlHash);
                state = State.OPEN;
            } else if (state == State.CLOSED && failures >= FAILURE_THRESHOLD) {
                log.warn("Circuit tripped: chain={} url_hash={} — CLOSED → OPEN after {} failures",
                        chain, urlHash, failures);
                state = State.OPEN;
            }
        }

        /**
         * Returns {@code true} if this provider should receive traffic.
         * CLOSED and HALF_OPEN providers are considered healthy.
         */
        boolean isHealthy() {
            return state != State.OPEN;
        }

        /**
         * Attempts to transition from OPEN → HALF_OPEN if the reset timeout
         * has elapsed. Returns {@code true} if the transition succeeded.
         */
        synchronized boolean attemptReset() {
            if (state == State.OPEN
                    && System.currentTimeMillis() - lastFailureTime >= RESET_TIMEOUT_MS) {
                log.info("Reset timeout elapsed: chain={} url_hash={} — OPEN → HALF_OPEN", chain, urlHash);
                state = State.HALF_OPEN;
                return true;
            }
            return false;
        }

        /**
         * Forces a transition to HALF_OPEN regardless of timing.
         * Used as a last resort when all providers are unhealthy.
         */
        synchronized void forceHalfOpen() {
            state = State.HALF_OPEN;
        }

        State getState() {
            return state;
        }
    }

    // =========================================================================
    // Exception
    // =========================================================================

    /**
     * Unchecked exception for RPC communication failures.
     */
    public static class RpcException extends RuntimeException {
        public RpcException(String message) {
            super(message);
        }

        public RpcException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private IndexerProperties.ChainConfig getChainConfig(String chain) {
        IndexerProperties.ChainConfig config = properties.getChains().get(chain);
        if (config == null) {
            throw new RpcException("Unknown chain: " + chain);
        }
        return config;
    }

    private static RpcProviderHealth.CircuitState toEntityState(RpcProvider.State state) {
        return switch (state) {
            case CLOSED -> RpcProviderHealth.CircuitState.CLOSED;
            case OPEN -> RpcProviderHealth.CircuitState.OPEN;
            case HALF_OPEN -> RpcProviderHealth.CircuitState.HALF_OPEN;
        };
    }

    static String hashUrl(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
