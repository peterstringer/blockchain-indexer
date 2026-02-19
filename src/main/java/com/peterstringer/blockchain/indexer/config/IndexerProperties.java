package com.peterstringer.blockchain.indexer.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "indexer")
public class IndexerProperties {

    @Valid
    @NotEmpty(message = "At least one chain must be configured")
    private Map<String, ChainConfig> chains = new HashMap<>();

    @Valid
    @NotNull
    private ConcurrencyConfig concurrency = new ConcurrencyConfig();

    @Valid
    @NotNull
    private ParquetConfig parquet = new ParquetConfig();

    @Valid
    @NotNull
    private DemoConfig demo = new DemoConfig();

    // -------------------------------------------------
    // Chain Configuration
    // -------------------------------------------------
    @Getter
    @Setter
    public static class ChainConfig {

        @NotBlank(message = "Chain name is required")
        private String name;

        @Positive(message = "Chain ID must be positive")
        private long chainId;

        @NotEmpty(message = "At least one RPC URL is required")
        private List<String> rpcUrls;

        @Min(value = 0, message = "Start block must be >= 0")
        private long startBlock;

        private Long endBlock;

        @Positive
        private int maxRetriesPerBlock = 3;

        @Positive
        private long retryDelayMs = 1000;

        @Positive
        private int rateLimitRequestsPerSecond = 10;
    }

    // -------------------------------------------------
    // Concurrency Configuration
    // -------------------------------------------------
    @Getter
    @Setter
    public static class ConcurrencyConfig {

        @Positive
        private int workerThreads = 10;

        @Positive
        private int batchSize = 50;

        @Positive
        private int maxQueueSize = 500;
    }

    // -------------------------------------------------
    // Parquet Configuration
    // -------------------------------------------------
    @Getter
    @Setter
    public static class ParquetConfig {

        @NotBlank
        private String outputPath = "./output";

        @NotBlank
        private String compressionCodec = "SNAPPY";

        private boolean partitionByChain = true;

        private boolean partitionByDate = true;

        @Positive
        private long rowGroupSize = 128 * 1024 * 1024; // 128 MB

        @Positive
        private long pageSize = 1024 * 1024; // 1 MB
    }

    // -------------------------------------------------
    // Demo Configuration
    // -------------------------------------------------
    @Getter
    @Setter
    public static class DemoConfig {

        private boolean enabled = false;

        @Positive
        private int syntheticBlockCount = 1000;
    }
}
