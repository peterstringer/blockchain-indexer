package com.peterstringer.blockchain.indexer.service;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.model.BlockAnalytics;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service for exporting {@link BlockAnalytics} data as CSV or Parquet.
 *
 * <p>Defines column metadata (for the frontend column picker) and handles
 * format-specific writing. Parquet uses the same {@link AvroParquetWriter}
 * approach as {@link ParquetWriterService}.
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    /** Column definition for the export pipeline. */
    public record ExportColumn(String key, String label, String group, String avroType,
                                Function<BlockAnalytics, Object> getter) {}

    /** All exportable columns, in display order. */
    private static final List<ExportColumn> ALL_COLUMNS = List.of(
            // Block Info
            col("chain",            "Chain",                  "Block Info",          "string",  BlockAnalytics::getChain),
            col("block_number",     "Block Number",           "Block Info",          "long",    BlockAnalytics::getBlockNumber),
            col("block_timestamp",  "Block Timestamp",        "Block Info",          "string",  b -> b.getBlockTimestamp() != null ? b.getBlockTimestamp().toString() : null),
            col("block_date",       "Block Date",             "Block Info",          "string",  b -> b.getBlockDate() != null ? b.getBlockDate().toString() : null),
            col("block_hour",       "Block Hour",             "Block Info",          "int",     b -> b.getBlockHour() != null ? b.getBlockHour().intValue() : null),
            // Gas Pricing
            col("base_fee_gwei",       "Base Fee (Gwei)",        "Gas Pricing",      "double",  BlockAnalytics::getBaseFeeGwei),
            col("avg_gas_price_gwei",  "Avg Gas Price (Gwei)",   "Gas Pricing",      "double",  BlockAnalytics::getAvgGasPriceGwei),
            col("min_gas_price_gwei",  "Min Gas Price (Gwei)",   "Gas Pricing",      "double",  BlockAnalytics::getMinGasPriceGwei),
            col("max_gas_price_gwei",  "Max Gas Price (Gwei)",   "Gas Pricing",      "double",  BlockAnalytics::getMaxGasPriceGwei),
            col("avg_priority_fee_gwei","Avg Priority Fee (Gwei)","Gas Pricing",     "double",  BlockAnalytics::getAvgPriorityFeeGwei),
            // Block Fullness
            col("gas_used",            "Gas Used",               "Block Fullness",   "long",    BlockAnalytics::getGasUsed),
            col("gas_limit",           "Gas Limit",              "Block Fullness",   "long",    BlockAnalytics::getGasLimit),
            col("gas_used_percentage", "Gas Used %",             "Block Fullness",   "double",  BlockAnalytics::getGasUsedPercentage),
            // Transaction Counts
            col("transaction_count",   "Transaction Count",      "Transaction Counts","int",    BlockAnalytics::getTransactionCount),
            col("tx_count_legacy",     "Legacy Tx Count",        "Transaction Counts","int",    BlockAnalytics::getTxCountLegacy),
            col("tx_count_eip1559",    "EIP-1559 Tx Count",      "Transaction Counts","int",    BlockAnalytics::getTxCountEip1559),
            col("tx_count_contract",   "Contract Tx Count",      "Transaction Counts","int",    BlockAnalytics::getTxCountContract),
            col("tx_count_failed",     "Failed Tx Count",        "Transaction Counts","int",    BlockAnalytics::getTxCountFailed),
            // Gas per Type
            col("avg_gas_legacy",      "Avg Gas (Legacy)",       "Gas per Type",     "double",  BlockAnalytics::getAvgGasLegacy),
            col("avg_gas_eip1559",     "Avg Gas (EIP-1559)",     "Gas per Type",     "double",  BlockAnalytics::getAvgGasEip1559),
            col("avg_gas_contract",    "Avg Gas (Contract)",     "Gas per Type",     "double",  BlockAnalytics::getAvgGasContract)
    );

    private static final Map<String, ExportColumn> COLUMN_MAP = ALL_COLUMNS.stream()
            .collect(Collectors.toMap(ExportColumn::key, c -> c, (a, _) -> a, LinkedHashMap::new));

    private final Configuration hadoopConf;

    public ExportService(IndexerProperties properties) {
        this.hadoopConf = new Configuration();
        this.hadoopConf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());
    }

    /** Returns column metadata for the frontend column picker. */
    public List<Map<String, String>> getColumnMetadata() {
        return ALL_COLUMNS.stream().map(c -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("key", c.key());
            m.put("label", c.label());
            m.put("group", c.group());
            return m;
        }).toList();
    }

    /** Returns the set of all valid column keys. */
    public Set<String> validColumnKeys() {
        return COLUMN_MAP.keySet();
    }

    /** Resolves requested columns — returns all if none specified. */
    public List<ExportColumn> resolveColumns(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return ALL_COLUMNS;
        }
        return requested.stream()
                .filter(COLUMN_MAP::containsKey)
                .map(COLUMN_MAP::get)
                .toList();
    }

    // =========================================================================
    // CSV export
    // =========================================================================

    /**
     * Writes block analytics data as CSV to the output stream.
     * Includes UTF-8 BOM for Excel compatibility.
     */
    public void writeCsv(OutputStream out, List<BlockAnalytics> data, List<ExportColumn> columns) {
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        // UTF-8 BOM
        pw.print('\uFEFF');

        // Header
        pw.println(columns.stream().map(ExportColumn::key).collect(Collectors.joining(",")));

        // Rows
        for (BlockAnalytics row : data) {
            pw.println(columns.stream()
                    .map(c -> csvValue(c.getter().apply(row)))
                    .collect(Collectors.joining(",")));
        }
        pw.flush();
    }

    private static String csvValue(Object val) {
        if (val == null) return "";
        String s = val.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // =========================================================================
    // Parquet export
    // =========================================================================

    /**
     * Writes block analytics data as Parquet. Writes to a temp file first
     * (AvroParquetWriter requires a file path), then streams to the output.
     */
    public void writeParquet(OutputStream out, List<BlockAnalytics> data,
                             List<ExportColumn> columns) throws IOException {
        Schema schema = buildSchema(columns);
        Path tempFile = Files.createTempFile("export-", ".parquet");

        try {
            writeParquetFile(tempFile, schema, data, columns);
            Files.copy(tempFile, out);
            out.flush();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private Schema buildSchema(List<ExportColumn> columns) {
        SchemaBuilder.FieldAssembler<Schema> fields = SchemaBuilder.record("BlockAnalyticsExport")
                .namespace("com.peterstringer.blockchain.indexer.export")
                .fields();

        for (ExportColumn col : columns) {
            switch (col.avroType()) {
                case "string" -> fields.optionalString(col.key());
                case "long"   -> fields.optionalLong(col.key());
                case "int"    -> fields.optionalInt(col.key());
                case "double" -> fields.optionalDouble(col.key());
                default       -> fields.optionalString(col.key());
            }
        }

        return fields.endRecord();
    }

    private void writeParquetFile(Path filePath, Schema schema,
                                  List<BlockAnalytics> data,
                                  List<ExportColumn> columns) throws IOException {
        LocalOutputFile outputFile = new LocalOutputFile(filePath);
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(outputFile)
                .withSchema(schema)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withConf(hadoopConf)
                .build()) {

            for (BlockAnalytics row : data) {
                GenericRecord record = new GenericData.Record(schema);
                for (ExportColumn col : columns) {
                    Object value = col.getter().apply(row);
                    // Convert numeric wrappers — Avro expects exact types
                    if (value instanceof Short s) {
                        value = s.intValue();
                    } else if (value instanceof Integer && "long".equals(col.avroType())) {
                        value = ((Integer) value).longValue();
                    }
                    record.put(col.key(), value);
                }
                writer.write(record);
            }
        }

        log.info("Wrote Parquet export: {} rows, {} columns, file={}",
                data.size(), columns.size(), filePath);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static ExportColumn col(String key, String label, String group,
                                     String avroType, Function<BlockAnalytics, Object> getter) {
        return new ExportColumn(key, label, group, avroType, getter);
    }
}
