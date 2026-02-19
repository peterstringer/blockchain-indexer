package com.peterstringer.blockchain.indexer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for the gas price analytics endpoint.
 *
 * <p>Contains aggregated gas price data for a range of blocks on a
 * specific chain, suitable for charting and dashboard display.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GasPriceAggregation {

    private String chain;
    private long fromBlock;
    private long toBlock;
    private int blocksAnalyzed;

    /** Average base fee across all blocks in the range (wei). */
    private Long avgBaseFee;
    /** Average gas price across all blocks in the range (wei). */
    private Long avgGasPrice;
    /** Maximum gas price seen in any block in the range (wei). */
    private Long maxGasPrice;
    /** Minimum gas price seen in any block in the range (wei). */
    private Long minGasPrice;
    /** Average gas utilization percentage across blocks. */
    private Double avgGasUtilization;

    /** Per-block data points for time-series display. */
    private List<BlockGasData> dataPoints;

    /**
     * Gas price data for a single block.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BlockGasData {
        private long blockNumber;
        private Long timestamp;
        private Long baseFeePerGas;
        private Long avgGasPrice;
        private Double gasUsedPercentage;
        private int transactionCount;
    }
}
