package com.peterstringer.blockchain.indexer.integration;

import com.peterstringer.blockchain.indexer.model.IndexedBlock;
import com.peterstringer.blockchain.indexer.service.ParquetWriterService;
import com.peterstringer.blockchain.indexer.service.SyntheticDataProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for the Parquet writing pipeline.
 *
 * <p>Validates buffering, schema conversion, and partition tracking
 * using real synthetic data from {@link SyntheticDataProvider}.
 *
 * <p><b>Note:</b> Actual Parquet file I/O tests are excluded because
 * Hadoop 3.4.x uses {@code Subject.getSubject()} which was removed
 * in JDK 24+. The buffering and schema logic is fully testable without
 * filesystem access.
 */
@DisplayName("Parquet Integration")
class ParquetIntegrationIT extends AbstractIntegrationTest {

    @Autowired
    private ParquetWriterService parquetWriterService;

    @Autowired
    private SyntheticDataProvider syntheticDataProvider;

    // =========================================================================
    // Buffering with real synthetic data
    // =========================================================================

    @Nested
    @DisplayName("Buffering with Synthetic Data")
    class BufferingTests {

        @Test
        @DisplayName("should buffer synthetic blocks correctly")
        void buffersSyntheticBlocks() {
            List<IndexedBlock> blocks = generateBlocks("ethereum", 0, 14);

            parquetWriterService.writeBlocks(blocks);

            Map<String, Object> stats = parquetWriterService.getStatistics();
            assertThat((long) stats.get("bufferedBlocks")).isGreaterThanOrEqualTo(15L);
        }

        @Test
        @DisplayName("should track embedded transactions from synthetic blocks")
        void tracksEmbeddedTransactions() {
            List<IndexedBlock> blocks = generateBlocks("ethereum", 0, 4);

            // Count expected transactions
            int expectedTxCount = blocks.stream()
                    .mapToInt(b -> b.getTransactions().size())
                    .sum();

            // Record totals before write (auto-flush may have drained previous buffers)
            Map<String, Object> before = parquetWriterService.getStatistics();
            long totalBefore = (long) before.get("bufferedTransactions")
                    + (long) before.get("totalTransactionsWritten");

            parquetWriterService.writeBlocks(blocks);

            // Check that total transactions (buffered + flushed) increased by at least expectedTxCount
            Map<String, Object> after = parquetWriterService.getStatistics();
            long totalAfter = (long) after.get("bufferedTransactions")
                    + (long) after.get("totalTransactionsWritten");
            assertThat(totalAfter - totalBefore).isGreaterThanOrEqualTo(expectedTxCount);
        }

        @Test
        @DisplayName("should partition blocks by chain")
        void partitionsBlocksByChain() {
            List<IndexedBlock> ethBlocks = generateBlocks("ethereum", 0, 2);
            List<IndexedBlock> polyBlocks = generateBlocks("polygon", 0, 2);

            parquetWriterService.writeBlocks(ethBlocks);
            parquetWriterService.writeBlocks(polyBlocks);

            Map<String, Object> stats = parquetWriterService.getStatistics();
            assertThat((int) stats.get("bufferedBlockPartitions")).isGreaterThanOrEqualTo(2);
        }
    }

    // =========================================================================
    // Schema validation
    // =========================================================================

    @Nested
    @DisplayName("Schema Validation")
    class SchemaValidationTests {

        @Test
        @DisplayName("should convert synthetic blocks to Avro records without errors")
        void convertsSyntheticBlocks() {
            List<IndexedBlock> blocks = generateBlocks("ethereum", 0, 9);

            // writeBlocks internally calls toBlockRecord and toTransactionRecord
            assertThatCode(() -> parquetWriterService.writeBlocks(blocks))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle blocks with null optional fields")
        void handlesNullFields() {
            IndexedBlock block = IndexedBlock.builder()
                    .chain("ethereum")
                    .chainId(1L)
                    .blockNumber(999L)
                    .blockHash("0x" + "a".repeat(64))
                    .parentHash("0x" + "b".repeat(64))
                    .timestamp(1_700_000_000L)
                    .miner("0x" + "0".repeat(40))
                    .gasLimit(30_000_000L)
                    .gasUsed(15_000_000L)
                    .gasUsedPercentage(50.0)
                    .transactionCount(0)
                    // Intentionally null: baseFeePerGas, avgGasPrice, etc.
                    .transactions(Collections.emptyList())
                    .build();

            assertThatCode(() -> parquetWriterService.writeBlocks(List.of(block)))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Flush resilience
    // =========================================================================

    @Nested
    @DisplayName("Flush Resilience")
    class FlushResilienceTests {

        @Test
        @DisplayName("should not crash on flushAll despite Hadoop JDK incompatibility")
        void flushAllDoesNotCrash() {
            List<IndexedBlock> blocks = generateBlocks("ethereum", 0, 4);
            parquetWriterService.writeBlocks(blocks);

            // flushAll should catch the Hadoop error internally
            assertThatCode(() -> parquetWriterService.flushAll())
                    .doesNotThrowAnyException();

            // Service should still be operational
            Map<String, Object> stats = parquetWriterService.getStatistics();
            assertThat(stats).isNotNull();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<IndexedBlock> generateBlocks(String chain, long from, long to) {
        List<IndexedBlock> blocks = new ArrayList<>();
        for (long i = from; i <= to; i++) {
            blocks.add(syntheticDataProvider.generateBlock(chain, i));
        }
        return blocks;
    }
}
