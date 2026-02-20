package com.peterstringer.blockchain.indexer.service;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.model.IndexedBlock;
import com.peterstringer.blockchain.indexer.model.IndexedTransaction;
import jakarta.annotation.PreDestroy;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for writing {@link IndexedBlock} and {@link IndexedTransaction} data
 * to Apache Parquet files.
 *
 * <h2>Why Parquet?</h2>
 * <p>Parquet is a columnar storage format optimized for analytical workloads.
 * Blockchain data is predominantly read in column-oriented patterns — e.g.
 * "give me all gas prices for the last 1000 blocks" — so Parquet's column
 * pruning and predicate pushdown yield order-of-magnitude speedups compared
 * to row-oriented formats like JSON or CSV. SNAPPY compression on columnar
 * data with high cardinality (addresses, hashes) and low cardinality (chain,
 * type) typically achieves 4–8× compression ratios.
 *
 * <h2>Schema Design</h2>
 * <p>Two Avro schemas are defined programmatically:
 * <ul>
 *   <li><b>Block schema</b> — one record per block with gas analytics and
 *       transaction-count aggregates. Large string fields (logsBloom, mixHash)
 *       are excluded to keep file sizes manageable.</li>
 *   <li><b>Transaction schema</b> — one record per transaction, denormalized
 *       with chain/block identification for self-contained querying. The raw
 *       {@code input} calldata is excluded (only {@code input_length} is stored)
 *       to avoid bloating Parquet files with unbounded binary data.</li>
 * </ul>
 * <p>Nullable fields use Avro union types ({@code ["null", "long"]}) for
 * fields that may be absent (e.g. {@code base_fee_per_gas} on pre-EIP-1559
 * blocks, {@code to_address} on contract creation transactions).
 *
 * <h2>Partitioning Strategy</h2>
 * <p>Files are partitioned by chain and date (configurable):
 * <pre>
 *   output/
 *   └── ethereum/
 *       └── 2024-01-15/
 *           ├── blocks_1705312000000.parquet
 *           └── transactions_1705312000000.parquet
 * </pre>
 * <p>This Hive-compatible layout enables efficient partition pruning in
 * query engines (Spark, DuckDB, Trino) and keeps individual file sizes
 * bounded for parallel processing.
 *
 * <h2>Buffer Flushing</h2>
 * <p>Records are buffered in memory per partition key. When the buffer reaches
 * {@value #BUFFER_FLUSH_THRESHOLD} records, it is automatically flushed to a
 * new Parquet file. Each flush creates a new file (timestamped) so no writer
 * is held open across batches. The {@link #flushAll()} method drains all
 * partitions and is called on shutdown.
 *
 * <h2>Thread Safety</h2>
 * <p>Partition buffers use {@link ConcurrentHashMap} with synchronized lists.
 * Flush operations acquire the list's monitor to atomically drain and write.
 * Multiple threads may buffer concurrently; flushes are serialized per
 * partition to prevent overlapping writes to the same directory.
 */
@Service
public class ParquetWriterService {

    private static final Logger log = LoggerFactory.getLogger(ParquetWriterService.class);

    private static final int BUFFER_FLUSH_THRESHOLD = 1000;

    private final IndexerProperties.ParquetConfig parquetConfig;
    private final boolean partitionByChain;
    private final boolean partitionByDate;
    private final CompressionCodecName compressionCodec;
    private final Configuration hadoopConf;

    // Partition key → synchronized buffer
    private final ConcurrentHashMap<String, List<IndexedBlock>> blockBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<IndexedTransaction>> txBuffers = new ConcurrentHashMap<>();

    // Statistics
    private final AtomicLong totalBlocksWritten = new AtomicLong(0);
    private final AtomicLong totalTransactionsWritten = new AtomicLong(0);
    private final AtomicLong totalFilesWritten = new AtomicLong(0);
    private final AtomicLong totalBytesWritten = new AtomicLong(0);

    // =========================================================================
    // Avro schemas
    // =========================================================================

    static final Schema BLOCK_SCHEMA = SchemaBuilder.record("Block")
            .namespace("com.peterstringer.blockchain.indexer")
            .fields()
            .requiredString("chain")
            .requiredLong("chain_id")
            .requiredLong("block_number")
            .requiredString("block_hash")
            .requiredString("parent_hash")
            .requiredLong("timestamp")
            .requiredString("date")
            .requiredString("indexed_at")
            .requiredString("miner")
            .optionalString("difficulty")
            .optionalString("total_difficulty")
            .optionalLong("size")
            .requiredLong("gas_limit")
            .requiredLong("gas_used")
            .requiredDouble("gas_used_percentage")
            .optionalLong("base_fee_per_gas")
            .optionalLong("avg_gas_price")
            .optionalLong("median_gas_price")
            .optionalLong("max_gas_price")
            .optionalLong("min_gas_price")
            .requiredInt("transaction_count")
            .optionalString("total_value")
            .optionalString("extra_data")
            .optionalString("nonce")
            .endRecord();

    static final Schema TRANSACTION_SCHEMA = SchemaBuilder.record("Transaction")
            .namespace("com.peterstringer.blockchain.indexer")
            .fields()
            .requiredString("chain")
            .requiredLong("chain_id")
            .requiredString("tx_hash")
            .requiredLong("block_number")
            .requiredString("block_hash")
            .requiredInt("transaction_index")
            .requiredString("from_address")
            .optionalString("to_address")
            .optionalString("contract_address")
            .requiredString("value")
            .requiredLong("gas")
            .optionalLong("gas_price")
            .optionalLong("max_fee_per_gas")
            .optionalLong("max_priority_fee_per_gas")
            .optionalLong("effective_gas_price")
            .optionalLong("gas_used")
            .requiredInt("tx_type")
            .requiredBoolean("success")
            .optionalString("status")
            .requiredInt("input_length")
            .requiredLong("nonce")
            .endRecord();

    public ParquetWriterService(IndexerProperties properties) {
        this.parquetConfig = properties.getParquet();
        this.partitionByChain = parquetConfig.isPartitionByChain();
        this.partitionByDate = parquetConfig.isPartitionByDate();
        this.compressionCodec = CompressionCodecName.valueOf(
                parquetConfig.getCompressionCodec().toUpperCase());
        this.hadoopConf = new Configuration();
        this.hadoopConf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());

        log.info("ParquetWriterService initialized: output={}, compression={}, "
                        + "partitionByChain={}, partitionByDate={}, rowGroupSize={}, pageSize={}",
                parquetConfig.getOutputPath(), compressionCodec,
                partitionByChain, partitionByDate,
                parquetConfig.getRowGroupSize(), parquetConfig.getPageSize());
    }

    // =========================================================================
    // Public API — buffering
    // =========================================================================

    /**
     * Buffers a single block for writing. Automatically flushes the partition
     * when the buffer reaches the threshold.
     *
     * @param block the indexed block to write
     */
    public void writeBlock(IndexedBlock block) {
        String partitionKey = partitionKey(block.getChain(), block.getTimestamp());
        List<IndexedBlock> buffer = blockBuffers.computeIfAbsent(
                partitionKey, _ -> Collections.synchronizedList(new ArrayList<>()));
        buffer.add(block);
        log.debug("Buffered block {} for partition={} (buffer_size={})",
                block.getBlockNumber(), partitionKey, buffer.size());
        if (buffer.size() >= BUFFER_FLUSH_THRESHOLD) {
            flush(partitionKey);
        }
    }

    /**
     * Buffers a single transaction for writing.
     *
     * @param tx the indexed transaction to write
     */
    public void writeTransaction(IndexedTransaction tx) {
        String partitionKey = partitionKey(tx.getChain(), tx.getBlockNumber());
        List<IndexedTransaction> buffer = txBuffers.computeIfAbsent(
                partitionKey, _ -> Collections.synchronizedList(new ArrayList<>()));
        buffer.add(tx);
        if (buffer.size() >= BUFFER_FLUSH_THRESHOLD) {
            flushTransactions(partitionKey);
        }
    }

    /**
     * Buffers a batch of blocks and their embedded transactions.
     *
     * @param blocks the indexed blocks to write (transactions are extracted automatically)
     */
    public void writeBlocks(List<IndexedBlock> blocks) {
        for (IndexedBlock block : blocks) {
            writeBlock(block);
            for (IndexedTransaction tx : block.getTransactions()) {
                writeTransaction(tx);
            }
        }
    }

    // =========================================================================
    // Public API — flushing
    // =========================================================================

    /**
     * Flushes both block and transaction buffers for a specific partition.
     *
     * @param partitionKey the partition key (e.g. "ethereum/2024-01-15")
     */
    public void flush(String partitionKey) {
        flushBlocks(partitionKey);
        flushTransactions(partitionKey);
    }

    /**
     * Flushes all buffered data across all partitions. Called on shutdown.
     */
    @PreDestroy
    public void flushAll() {
        log.info("Flushing all Parquet buffers...");
        blockBuffers.keySet().forEach(this::flushBlocks);
        txBuffers.keySet().forEach(this::flushTransactions);
        log.info("Parquet flush complete: {} blocks, {} transactions across {} files ({} bytes)",
                totalBlocksWritten.get(), totalTransactionsWritten.get(),
                totalFilesWritten.get(), totalBytesWritten.get());
    }

    /**
     * Returns current write statistics.
     *
     * @return map of statistic name → value
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalBlocksWritten", totalBlocksWritten.get());
        stats.put("totalTransactionsWritten", totalTransactionsWritten.get());
        stats.put("totalFilesWritten", totalFilesWritten.get());
        stats.put("totalBytesWritten", totalBytesWritten.get());
        stats.put("bufferedBlockPartitions", blockBuffers.size());
        stats.put("bufferedTransactionPartitions", txBuffers.size());

        long bufferedBlocks = blockBuffers.values().stream().mapToInt(List::size).sum();
        long bufferedTxs = txBuffers.values().stream().mapToInt(List::size).sum();
        stats.put("bufferedBlocks", bufferedBlocks);
        stats.put("bufferedTransactions", bufferedTxs);
        return stats;
    }

    // =========================================================================
    // Internal flush logic
    // =========================================================================

    private void flushBlocks(String partitionKey) {
        List<IndexedBlock> buffer = blockBuffers.get(partitionKey);
        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        List<IndexedBlock> toWrite;
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return;
            }
            toWrite = new ArrayList<>(buffer);
            buffer.clear();
        }

        String filePath = buildFilePath(partitionKey, "blocks");
        log.info("Flushing {} blocks to {}", toWrite.size(), filePath);

        try {
            ensureDirectoryExists(filePath);
            long bytesWritten = writeParquetFile(filePath, BLOCK_SCHEMA,
                    toWrite.stream().map(this::toBlockRecord).toList());
            totalBlocksWritten.addAndGet(toWrite.size());
            totalFilesWritten.incrementAndGet();
            totalBytesWritten.addAndGet(bytesWritten);
            log.info("Wrote {} blocks to {} ({} bytes)", toWrite.size(), filePath, bytesWritten);
        } catch (IOException e) {
            log.error("Failed to write blocks to {}: {} — re-buffering {} records",
                    filePath, e.getMessage(), toWrite.size());
            // Re-buffer on failure so data is not lost
            List<IndexedBlock> buf = blockBuffers.computeIfAbsent(
                    partitionKey, _ -> Collections.synchronizedList(new ArrayList<>()));
            synchronized (buf) {
                buf.addAll(0, toWrite);
            }
        }
    }

    private void flushTransactions(String partitionKey) {
        List<IndexedTransaction> buffer = txBuffers.get(partitionKey);
        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        List<IndexedTransaction> toWrite;
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return;
            }
            toWrite = new ArrayList<>(buffer);
            buffer.clear();
        }

        String filePath = buildFilePath(partitionKey, "transactions");
        log.info("Flushing {} transactions to {}", toWrite.size(), filePath);

        try {
            ensureDirectoryExists(filePath);
            long bytesWritten = writeParquetFile(filePath, TRANSACTION_SCHEMA,
                    toWrite.stream().map(this::toTransactionRecord).toList());
            totalTransactionsWritten.addAndGet(toWrite.size());
            totalFilesWritten.incrementAndGet();
            totalBytesWritten.addAndGet(bytesWritten);
            log.info("Wrote {} transactions to {} ({} bytes)", toWrite.size(), filePath, bytesWritten);
        } catch (IOException e) {
            log.error("Failed to write transactions to {}: {} — re-buffering {} records",
                    filePath, e.getMessage(), toWrite.size());
            List<IndexedTransaction> buf = txBuffers.computeIfAbsent(
                    partitionKey, _ -> Collections.synchronizedList(new ArrayList<>()));
            synchronized (buf) {
                buf.addAll(0, toWrite);
            }
        }
    }

    // =========================================================================
    // Parquet file writing
    // =========================================================================

    private long writeParquetFile(String filePath, Schema schema,
                                  List<GenericRecord> records) throws IOException {
        LocalOutputFile outputFile = new LocalOutputFile(java.nio.file.Path.of(filePath));
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(outputFile)
                .withSchema(schema)
                .withCompressionCodec(compressionCodec)
                .withRowGroupSize(parquetConfig.getRowGroupSize())
                .withPageSize((int) parquetConfig.getPageSize())
                .withWriteMode(ParquetFileWriter.Mode.CREATE)
                .withConf(hadoopConf)
                .build()) {
            for (GenericRecord record : records) {
                writer.write(record);
            }
        }
        java.io.File file = new java.io.File(filePath);
        return file.exists() ? file.length() : 0;
    }

    // =========================================================================
    // Record conversion
    // =========================================================================

    private GenericRecord toBlockRecord(IndexedBlock block) {
        GenericRecord record = new GenericData.Record(BLOCK_SCHEMA);
        record.put("chain", block.getChain());
        record.put("chain_id", block.getChainId());
        record.put("block_number", block.getBlockNumber());
        record.put("block_hash", block.getBlockHash());
        record.put("parent_hash", block.getParentHash());
        record.put("timestamp", block.getTimestamp());
        record.put("date", epochToDate(block.getTimestamp()));
        record.put("indexed_at", block.getIndexedAt() != null ? block.getIndexedAt().toString() : Instant.now().toString());
        record.put("miner", block.getMiner());
        record.put("difficulty", block.getDifficulty());
        record.put("total_difficulty", block.getTotalDifficulty());
        record.put("size", block.getSize());
        record.put("gas_limit", block.getGasLimit());
        record.put("gas_used", block.getGasUsed());
        record.put("gas_used_percentage", block.getGasUsedPercentage() != null ? block.getGasUsedPercentage() : 0.0);
        record.put("base_fee_per_gas", block.getBaseFeePerGas());
        record.put("avg_gas_price", block.getAvgGasPrice());
        record.put("median_gas_price", block.getMedianGasPrice());
        record.put("max_gas_price", block.getMaxGasPrice());
        record.put("min_gas_price", block.getMinGasPrice());
        record.put("transaction_count", block.getTransactionCount() != null ? block.getTransactionCount() : 0);
        record.put("total_value", block.getTotalValue());
        record.put("extra_data", block.getExtraData());
        record.put("nonce", block.getNonce());
        return record;
    }

    private GenericRecord toTransactionRecord(IndexedTransaction tx) {
        GenericRecord record = new GenericData.Record(TRANSACTION_SCHEMA);
        record.put("chain", tx.getChain());
        record.put("chain_id", tx.getChainId());
        record.put("tx_hash", tx.getHash());
        record.put("block_number", tx.getBlockNumber());
        record.put("block_hash", tx.getBlockHash());
        record.put("transaction_index", tx.getTransactionIndex() != null ? tx.getTransactionIndex() : 0);
        record.put("from_address", tx.getFrom());
        record.put("to_address", tx.getTo());
        record.put("contract_address", tx.getContractAddress());
        record.put("value", tx.getValue());
        record.put("gas", tx.getGas());
        record.put("gas_price", tx.getGasPrice());
        record.put("max_fee_per_gas", tx.getMaxFeePerGas());
        record.put("max_priority_fee_per_gas", tx.getMaxPriorityFeePerGas());
        record.put("effective_gas_price", tx.getEffectiveGasPrice());
        record.put("gas_used", tx.getGasUsed());
        record.put("tx_type", tx.getType() != null ? tx.getType() : 0);
        record.put("success", tx.isSuccess());
        record.put("status", tx.getStatus());
        record.put("input_length", tx.getInputLength() != null ? tx.getInputLength() : 0);
        record.put("nonce", tx.getNonce() != null ? tx.getNonce() : 0L);
        return record;
    }

    // =========================================================================
    // Partitioning & file paths
    // =========================================================================

    String partitionKey(String chain, Long timestampOrBlockNumber) {
        StringBuilder key = new StringBuilder();
        if (partitionByChain && chain != null) {
            key.append(chain.toLowerCase());
        }
        if (partitionByDate && timestampOrBlockNumber != null) {
            // Heuristic: values > 1_000_000_000 are epoch seconds, otherwise block numbers
            // For block numbers without timestamps, use current date
            String date;
            if (timestampOrBlockNumber > 1_000_000_000L) {
                date = epochToDate(timestampOrBlockNumber);
            } else {
                date = LocalDate.now(ZoneOffset.UTC).toString();
            }
            if (!key.isEmpty()) {
                key.append("/");
            }
            key.append(date);
        }
        return key.isEmpty() ? "default" : key.toString();
    }

    private String buildFilePath(String partitionKey, String prefix) {
        String basePath = parquetConfig.getOutputPath();
        long timestamp = System.currentTimeMillis();
        return "%s/%s/%s_%d.parquet".formatted(basePath, partitionKey, prefix, timestamp);
    }

    private static void ensureDirectoryExists(String filePath) throws IOException {
        java.nio.file.Path parent = java.nio.file.Path.of(filePath).getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String epochToDate(long epochSeconds) {
        return LocalDate.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC).toString();
    }
}
