package com.peterstringer.blockchain.indexer.controller;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.model.BlockAnalytics;
import com.peterstringer.blockchain.indexer.repository.BlockAnalyticsRepository;
import com.peterstringer.blockchain.indexer.service.ExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for exporting block analytics data as CSV or Parquet.
 *
 * <p>Provides a metadata endpoint for the frontend column picker and a
 * streaming download endpoint that supports chain, date range, column,
 * and format filtering.
 *
 * <p>Base path: {@code /api/export}
 */
@RestController
@RequestMapping("/api/export")
@Tag(name = "Export", description = "Export block analytics data in CSV or Parquet format")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private final BlockAnalyticsRepository repository;
    private final ExportService exportService;
    private final IndexerProperties properties;

    public ExportController(BlockAnalyticsRepository repository,
                            ExportService exportService,
                            IndexerProperties properties) {
        this.repository = repository;
        this.exportService = exportService;
        this.properties = properties;
    }

    // =========================================================================
    // GET /metadata
    // =========================================================================

    /**
     * Returns export metadata: available chains, date ranges, row counts,
     * and column definitions for the frontend column picker.
     */
    @Operation(summary = "Get export metadata",
            description = "Returns available chains, date range of indexed data, total rows, and column definitions.")
    @ApiResponse(responseCode = "200", description = "Metadata retrieved successfully")
    @GetMapping("/metadata")
    public ResponseEntity<Map<String, Object>> getMetadata() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Available chains from config
        result.put("chains", properties.getChains().keySet().stream().sorted().toList());

        // Data availability from DB
        List<Object[]> availability = repository.findDataAvailability();
        String earliestDate = null;
        String latestDate = null;
        long totalRows = 0;

        for (Object[] row : availability) {
            String earliest = row[1] != null ? row[1].toString() : null;
            String latest = row[2] != null ? row[2].toString() : null;
            long count = row[3] != null ? ((Number) row[3]).longValue() : 0;
            totalRows += count;

            if (earliest != null && (earliestDate == null || earliest.compareTo(earliestDate) < 0)) {
                earliestDate = earliest;
            }
            if (latest != null && (latestDate == null || latest.compareTo(latestDate) > 0)) {
                latestDate = latest;
            }
        }

        result.put("earliestDate", earliestDate);
        result.put("latestDate", latestDate);
        result.put("totalRows", totalRows);
        result.put("columns", exportService.getColumnMetadata());

        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // GET /block-analytics
    // =========================================================================

    /**
     * Exports block analytics data as a streaming CSV or Parquet download.
     *
     * @param from    start date (inclusive)
     * @param to      end date (inclusive)
     * @param chain   optional chain filter (null = all chains)
     * @param format  output format: "csv" (default) or "parquet"
     * @param columns optional column keys to include (default = all)
     * @return streaming file download
     */
    @Operation(summary = "Export block analytics",
            description = "Downloads block analytics data filtered by chain, date range, and columns. Supports CSV and Parquet formats.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "File download started"),
            @ApiResponse(responseCode = "400", description = "Invalid parameters")
    })
    @GetMapping("/block-analytics")
    public ResponseEntity<StreamingResponseBody> exportBlockAnalytics(
            @Parameter(description = "Start date (YYYY-MM-DD)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (YYYY-MM-DD)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Chain filter (e.g. ethereum). Omit for all chains.")
            @RequestParam(required = false) String chain,
            @Parameter(description = "Output format: csv or parquet")
            @RequestParam(defaultValue = "csv") String format,
            @Parameter(description = "Column keys to include. Omit for all columns.")
            @RequestParam(required = false) List<String> columns) {

        // Validate format
        if (!"csv".equalsIgnoreCase(format) && !"parquet".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("Format must be 'csv' or 'parquet', got: " + format);
        }

        // Validate date range
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' date must be before or equal to 'to' date");
        }

        // Validate columns
        if (columns != null && !columns.isEmpty()) {
            var valid = exportService.validColumnKeys();
            var invalid = columns.stream().filter(c -> !valid.contains(c)).toList();
            if (!invalid.isEmpty()) {
                throw new IllegalArgumentException("Unknown columns: " + invalid);
            }
        }

        var resolvedColumns = exportService.resolveColumns(columns);
        boolean isParquet = "parquet".equalsIgnoreCase(format);
        String ext = isParquet ? "parquet" : "csv";
        String chainLabel = chain != null ? chain : "all";
        String filename = "block-analytics_%s_%s_%s.%s".formatted(chainLabel, from, to, ext);

        log.info("Export requested: chain={}, from={}, to={}, format={}, columns={}",
                chainLabel, from, to, format, resolvedColumns.size());

        // Query data
        List<BlockAnalytics> data = repository.findForExport(chain, from, to);

        log.info("Export query returned {} rows", data.size());

        StreamingResponseBody stream = out -> {
            if (isParquet) {
                exportService.writeParquet(out, data, resolvedColumns);
            } else {
                exportService.writeCsv(out, data, resolvedColumns);
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData("attachment", filename);
        headers.set("X-Row-Count", String.valueOf(data.size()));

        MediaType mediaType = isParquet
                ? MediaType.APPLICATION_OCTET_STREAM
                : new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(mediaType)
                .body(stream);
    }
}
