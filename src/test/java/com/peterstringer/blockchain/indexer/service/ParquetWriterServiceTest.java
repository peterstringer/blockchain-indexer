package com.peterstringer.blockchain.indexer.service;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.model.IndexedBlock;
import com.peterstringer.blockchain.indexer.model.IndexedTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ParquetWriterService}, covering Avro schema
 * definitions, partitioning logic, batch buffering, and statistics.
 *
 * <p>Actual Parquet file I/O tests are excluded because Hadoop 3.4.x uses
 * {@code Subject.getSubject()} which was removed in JDK 24+. The buffering,
 * partitioning, and schema logic is fully testable without filesystem access.
 */
@DisplayName("ParquetWriterService")
class ParquetWriterServiceTest {

    @TempDir
    java.nio.file.Path tempDir;

    private ParquetWriterService service;
    private IndexerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IndexerProperties();

        IndexerProperties.ParquetConfig parquetConfig = new IndexerProperties.ParquetConfig();
        parquetConfig.setOutputPath(tempDir.toString());
        parquetConfig.setCompressionCodec("SNAPPY");
        parquetConfig.setPartitionByChain(true);
        parquetConfig.setPartitionByDate(true);
        parquetConfig.setRowGroupSize(1024 * 1024);
        parquetConfig.setPageSize(64 * 1024);
        properties.setParquet(parquetConfig);

        service = new ParquetWriterService(properties);
    }

    // =========================================================================
    // Schema tests
    // =========================================================================

    @Nested
    @DisplayName("Avro Schemas")
    class SchemaTests {

        @Test
        @DisplayName("block schema should contain all required fields")
        void blockSchemaHasRequiredFields() {
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("chain")).isNotNull();
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("chain_id")).isNotNull();
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("block_number")).isNotNull();
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("block_hash")).isNotNull();
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("parent_hash")).isNotNull();
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("timestamp")).isNotNull();
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("gas_limit")).isNotNull();
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("gas_used")).isNotNull();
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("transaction_count")).isNotNull();
        }

        @Test
        @DisplayName("block schema should contain optional gas pricing fields")
        void blockSchemaHasOptionalFields() {
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("base_fee_per_gas")).isNotNull();
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("avg_gas_price")).isNotNull();
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("median_gas_price")).isNotNull();
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("max_gas_price")).isNotNull();
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getField("min_gas_price")).isNotNull();
        }

        @Test
        @DisplayName("block schema should have correct field count")
        void blockSchemaFieldCount() {
            // chain, chain_id, block_number, block_hash, parent_hash, timestamp, date,
            // indexed_at, miner, difficulty, total_difficulty, size, gas_limit, gas_used,
            // gas_used_percentage, base_fee_per_gas, avg_gas_price, median_gas_price,
            // max_gas_price, min_gas_price, transaction_count, total_value, extra_data, nonce
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getFields()).hasSize(24);
        }

        @Test
        @DisplayName("transaction schema should contain all required fields")
        void transactionSchemaHasRequiredFields() {
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("chain")).isNotNull();
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("tx_hash")).isNotNull();
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("block_number")).isNotNull();
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("from_address")).isNotNull();
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("value")).isNotNull();
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("gas")).isNotNull();
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("tx_type")).isNotNull();
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("success")).isNotNull();
        }

        @Test
        @DisplayName("transaction schema should contain optional EIP-1559 fields")
        void transactionSchemaHasOptionalFields() {
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("to_address")).isNotNull();
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("max_fee_per_gas")).isNotNull();
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("max_priority_fee_per_gas")).isNotNull();
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("effective_gas_price")).isNotNull();
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getField("contract_address")).isNotNull();
        }

        @Test
        @DisplayName("transaction schema should have correct field count")
        void transactionSchemaFieldCount() {
            // chain, chain_id, tx_hash, block_number, block_hash, transaction_index,
            // from_address, to_address, contract_address, value, gas, gas_price,
            // max_fee_per_gas, max_priority_fee_per_gas, effective_gas_price, gas_used,
            // tx_type, success, status, input_length, nonce
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getFields()).hasSize(21);
        }

        @Test
        @DisplayName("schemas should have correct namespace")
        void schemasHaveCorrectNamespace() {
            assertThat(ParquetWriterService.BLOCK_SCHEMA.getNamespace())
                    .isEqualTo("com.peterstringer.blockchain.indexer");
            assertThat(ParquetWriterService.TRANSACTION_SCHEMA.getNamespace())
                    .isEqualTo("com.peterstringer.blockchain.indexer");
        }
    }

    // =========================================================================
    // Partitioning tests
    // =========================================================================

    @Nested
    @DisplayName("Partitioning")
    class PartitioningTests {

        @Test
        @DisplayName("should partition by chain and date when both enabled")
        void partitionsByChainAndDate() {
            // timestamp 1_700_000_000 = 2023-11-14 UTC
            String key = service.partitionKey("ethereum", 1_700_000_000L);
            assertThat(key).isEqualTo("ethereum/2023-11-14");
        }

        @Test
        @DisplayName("should partition by chain only when date disabled")
        void partitionsByChainOnly() {
            properties.getParquet().setPartitionByDate(false);
            ParquetWriterService chainOnlyService = new ParquetWriterService(properties);

            String key = chainOnlyService.partitionKey("ethereum", 1_700_000_000L);
            assertThat(key).isEqualTo("ethereum");
        }

        @Test
        @DisplayName("should partition by date only when chain disabled")
        void partitionsByDateOnly() {
            properties.getParquet().setPartitionByChain(false);
            ParquetWriterService dateOnlyService = new ParquetWriterService(properties);

            String key = dateOnlyService.partitionKey("ethereum", 1_700_000_000L);
            assertThat(key).isEqualTo("2023-11-14");
        }

        @Test
        @DisplayName("should use 'default' when both partitions disabled")
        void usesDefaultWhenNeitherEnabled() {
            properties.getParquet().setPartitionByChain(false);
            properties.getParquet().setPartitionByDate(false);
            ParquetWriterService noPartitionService = new ParquetWriterService(properties);

            String key = noPartitionService.partitionKey("ethereum", 1_700_000_000L);
            assertThat(key).isEqualTo("default");
        }

        @Test
        @DisplayName("should use current date for block numbers (< 1B)")
        void usesCurrentDateForBlockNumbers() {
            String key = service.partitionKey("ethereum", 18_000_000L);
            // Block number < 1B → treated as block number, uses current date
            assertThat(key).startsWith("ethereum/");
            // Date portion should be a valid ISO date
            String datePart = key.substring("ethereum/".length());
            assertThat(datePart).matches("\\d{4}-\\d{2}-\\d{2}");
        }

        @Test
        @DisplayName("should lowercase chain name in partition key")
        void lowercasesChainName() {
            String key = service.partitionKey("Ethereum", 1_700_000_000L);
            assertThat(key).startsWith("ethereum/");
        }

        @Test
        @DisplayName("should handle null chain gracefully")
        void handlesNullChain() {
            properties.getParquet().setPartitionByChain(true);
            properties.getParquet().setPartitionByDate(false);
            ParquetWriterService svc = new ParquetWriterService(properties);

            String key = svc.partitionKey(null, 1_700_000_000L);
            assertThat(key).isEqualTo("default");
        }

        @Test
        @DisplayName("should handle null timestamp gracefully")
        void handlesNullTimestamp() {
            properties.getParquet().setPartitionByChain(false);
            properties.getParquet().setPartitionByDate(true);
            ParquetWriterService svc = new ParquetWriterService(properties);

            String key = svc.partitionKey("ethereum", null);
            assertThat(key).isEqualTo("default");
        }
    }

    // =========================================================================
    // Buffering tests (no filesystem I/O)
    // =========================================================================

    @Nested
    @DisplayName("Buffering")
    class BufferingTests {

        @Test
        @DisplayName("should buffer blocks without immediate write")
        void buffersWithoutWrite() {
            IndexedBlock block = createTestBlock(1L, "ethereum", 1_700_000_000L);

            service.writeBlock(block);

            Map<String, Object> stats = service.getStatistics();
            assertThat((long) stats.get("bufferedBlocks")).isEqualTo(1);
            assertThat((long) stats.get("totalBlocksWritten")).isEqualTo(0);
        }

        @Test
        @DisplayName("writeBlocks should buffer embedded transactions")
        void writeBlocksBuffersTransactions() {
            IndexedTransaction tx1 = createTestTransaction(100L, "ethereum");
            IndexedTransaction tx2 = createTestTransaction(100L, "ethereum");
            IndexedBlock block = createTestBlock(100L, "ethereum", 1_700_000_000L);
            block.setTransactions(List.of(tx1, tx2));

            service.writeBlocks(List.of(block));

            Map<String, Object> stats = service.getStatistics();
            assertThat((long) stats.get("bufferedBlocks")).isEqualTo(1);
            assertThat((long) stats.get("bufferedTransactions")).isEqualTo(2);
        }

        @Test
        @DisplayName("should buffer blocks into correct partitions")
        void buffersIntoCorrectPartitions() {
            service.writeBlock(createTestBlock(1L, "ethereum", 1_700_000_000L));
            service.writeBlock(createTestBlock(2L, "polygon", 1_700_000_000L));

            Map<String, Object> stats = service.getStatistics();
            assertThat((int) stats.get("bufferedBlockPartitions")).isEqualTo(2);
        }

        @Test
        @DisplayName("should increment buffer count for same partition")
        void incrementsBufferForSamePartition() {
            // Same chain + same day → same partition
            service.writeBlock(createTestBlock(1L, "ethereum", 1_700_000_000L));
            service.writeBlock(createTestBlock(2L, "ethereum", 1_700_000_012L)); // 12s later, same day

            Map<String, Object> stats = service.getStatistics();
            assertThat((long) stats.get("bufferedBlocks")).isEqualTo(2);
            assertThat((int) stats.get("bufferedBlockPartitions")).isEqualTo(1);
        }

        @Test
        @DisplayName("should buffer single transaction via writeTransaction")
        void buffersSingleTransaction() {
            IndexedTransaction tx = createTestTransaction(100L, "ethereum");

            service.writeTransaction(tx);

            Map<String, Object> stats = service.getStatistics();
            assertThat((long) stats.get("bufferedTransactions")).isEqualTo(1);
        }
    }

    // =========================================================================
    // Statistics tests
    // =========================================================================

    @Nested
    @DisplayName("Statistics")
    class StatisticsTests {

        @Test
        @DisplayName("should track all statistic keys")
        void tracksAllKeys() {
            Map<String, Object> stats = service.getStatistics();

            assertThat(stats).containsKeys(
                    "totalBlocksWritten",
                    "totalTransactionsWritten",
                    "totalFilesWritten",
                    "totalBytesWritten",
                    "bufferedBlockPartitions",
                    "bufferedTransactionPartitions",
                    "bufferedBlocks",
                    "bufferedTransactions"
            );
        }

        @Test
        @DisplayName("should initialize all counters to zero")
        void initializesToZero() {
            Map<String, Object> stats = service.getStatistics();

            assertThat((long) stats.get("totalBlocksWritten")).isEqualTo(0);
            assertThat((long) stats.get("totalTransactionsWritten")).isEqualTo(0);
            assertThat((long) stats.get("totalFilesWritten")).isEqualTo(0);
            assertThat((long) stats.get("totalBytesWritten")).isEqualTo(0);
            assertThat((long) stats.get("bufferedBlocks")).isEqualTo(0);
            assertThat((long) stats.get("bufferedTransactions")).isEqualTo(0);
        }

        @Test
        @DisplayName("should count buffered partitions correctly")
        void countsPartitions() {
            service.writeBlock(createTestBlock(1L, "ethereum", 1_700_000_000L));
            service.writeBlock(createTestBlock(2L, "polygon", 1_700_000_000L));

            Map<String, Object> stats = service.getStatistics();
            assertThat((int) stats.get("bufferedBlockPartitions")).isEqualTo(2);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static IndexedBlock createTestBlock(long blockNumber, String chain, long timestamp) {
        return IndexedBlock.builder()
                .chain(chain)
                .chainId(1L)
                .blockNumber(blockNumber)
                .blockHash("0x" + "a".repeat(64))
                .parentHash("0x" + "b".repeat(64))
                .timestamp(timestamp)
                .indexedAt(Instant.now())
                .miner("0x" + "c".repeat(40))
                .difficulty("0")
                .totalDifficulty("0")
                .size(50_000L)
                .gasLimit(30_000_000L)
                .gasUsed(15_000_000L)
                .gasUsedPercentage(50.0)
                .baseFeePerGas(50_000_000_000L)
                .avgGasPrice(55_000_000_000L)
                .medianGasPrice(52_000_000_000L)
                .maxGasPrice(100_000_000_000L)
                .minGasPrice(20_000_000_000L)
                .transactionCount(200)
                .totalValue("5000000000000000000000")
                .extraData("0x")
                .nonce("0x0000000000000000")
                .transactions(Collections.emptyList())
                .build();
    }

    private static IndexedTransaction createTestTransaction(long blockNumber, String chain) {
        return IndexedTransaction.builder()
                .chain(chain)
                .chainId(1L)
                .hash("0x" + "d".repeat(64))
                .blockNumber(blockNumber)
                .blockHash("0x" + "a".repeat(64))
                .transactionIndex(0)
                .from("0x" + "e".repeat(40))
                .to("0x" + "f".repeat(40))
                .value("1000000000000000000")
                .gas(21_000L)
                .gasPrice(50_000_000_000L)
                .type(0)
                .success(true)
                .status("0x1")
                .input("0x")
                .inputLength(2)
                .nonce(0L)
                .build();
    }
}
