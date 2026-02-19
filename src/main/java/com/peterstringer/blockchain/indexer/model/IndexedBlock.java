package com.peterstringer.blockchain.indexer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.web3j.protocol.core.methods.response.EthBlock;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.LongSummaryStatistics;

/**
 * Normalized representation of a blockchain block with computed analytics.
 *
 * <p>This model captures the raw block data fetched via RPC plus derived metrics
 * (gas usage percentage, gas price statistics, total value transferred) that are
 * expensive to recompute later. All monetary values are stored as {@code Long}
 * in wei to avoid floating-point precision issues while staying within the range
 * of gas prices on EVM chains. The {@code totalValue} field uses {@code String}
 * because aggregate ether values can exceed {@code Long.MAX_VALUE}.
 *
 * <p>Designed for both Parquet serialization and API responses. The embedded
 * {@link IndexedTransaction} list is populated during indexing but excluded
 * from the Parquet block schema (transactions get their own Parquet files).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexedBlock {

    // ---- Chain identification ----
    private String chain;
    private Long chainId;

    // ---- Block header ----
    private Long blockNumber;
    private String blockHash;
    private String parentHash;
    private Long timestamp;
    private Instant indexedAt;

    // ---- Miner / consensus ----
    private String miner;
    private String difficulty;
    private String totalDifficulty;
    private Long size;

    // ---- Gas ----
    private Long gasLimit;
    private Long gasUsed;
    private Double gasUsedPercentage;

    // ---- Gas pricing (wei) ----
    /** Null for pre-EIP-1559 blocks. */
    private Long baseFeePerGas;
    private Long avgGasPrice;
    private Long medianGasPrice;
    private Long maxGasPrice;
    private Long minGasPrice;

    // ---- Transaction stats ----
    private Integer transactionCount;
    /** Stored as String to safely represent values exceeding Long.MAX_VALUE. */
    private String totalValue;

    // ---- Extra fields ----
    private String extraData;
    private String logsBloom;
    private String nonce;
    private String mixHash;

    // ---- Transactions (populated during indexing, not serialized to block Parquet) ----
    @Builder.Default
    private List<IndexedTransaction> transactions = Collections.emptyList();

    /**
     * Builds an {@code IndexedBlock} from a web3j {@link EthBlock.Block}.
     *
     * <p>Transactions are <em>not</em> populated here — they require receipt data
     * that is fetched separately. Call this method first, then attach transactions
     * once receipts have been resolved.
     *
     * @param chainName human-readable chain name (e.g. "ethereum")
     * @param chainId   EIP-155 chain ID
     * @param block     the raw block returned by the JSON-RPC provider
     * @return a fully populated {@code IndexedBlock} (minus transactions)
     */
    public static IndexedBlock fromWeb3jBlock(String chainName, Long chainId, EthBlock.Block block) {
        Long gasLimit = safeLongValue(block.getGasLimit());
        Long gasUsed = safeLongValue(block.getGasUsed());
        Double gasPercent = (gasLimit != null && gasLimit > 0 && gasUsed != null)
                ? (gasUsed * 100.0) / gasLimit
                : null;

        // Compute gas price statistics from transaction-level data
        List<BigInteger> gasPrices = block.getTransactions().stream()
                .map(txResult -> ((EthBlock.TransactionObject) txResult.get()).getGasPrice())
                .filter(gp -> gp != null && gp.signum() > 0)
                .toList();

        Long avgGas = null, medianGas = null, maxGas = null, minGas = null;
        if (!gasPrices.isEmpty()) {
            LongSummaryStatistics stats = gasPrices.stream()
                    .mapToLong(BigInteger::longValueExact)
                    .summaryStatistics();
            avgGas = (long) stats.getAverage();
            maxGas = stats.getMax();
            minGas = stats.getMin();

            List<Long> sorted = gasPrices.stream()
                    .map(BigInteger::longValueExact)
                    .sorted()
                    .toList();
            medianGas = sorted.get(sorted.size() / 2);
        }

        BigInteger totalVal = block.getTransactions().stream()
                .map(txResult -> ((EthBlock.TransactionObject) txResult.get()).getValue())
                .filter(v -> v != null)
                .reduce(BigInteger.ZERO, BigInteger::add);

        return IndexedBlock.builder()
                .chain(chainName)
                .chainId(chainId)
                .blockNumber(block.getNumber().longValueExact())
                .blockHash(block.getHash())
                .parentHash(block.getParentHash())
                .timestamp(safeLongValue(block.getTimestamp()))
                .indexedAt(Instant.now())
                .miner(block.getMiner())
                .difficulty(nullSafeBigInt(block.getDifficulty()))
                .totalDifficulty(nullSafeBigInt(block.getTotalDifficulty()))
                .size(safeLongValue(block.getSize()))
                .gasLimit(gasLimit)
                .gasUsed(gasUsed)
                .gasUsedPercentage(gasPercent)
                .baseFeePerGas(safeLongValue(block.getBaseFeePerGas()))
                .avgGasPrice(avgGas)
                .medianGasPrice(medianGas)
                .maxGasPrice(maxGas)
                .minGasPrice(minGas)
                .transactionCount(block.getTransactions().size())
                .totalValue(totalVal.toString())
                .extraData(block.getExtraData())
                .logsBloom(block.getLogsBloom())
                .nonce(nullSafeBigInt(block.getNonce()))
                .mixHash(block.getMixHash())
                .transactions(Collections.emptyList())
                .build();
    }

    private static Long safeLongValue(BigInteger value) {
        return value != null ? value.longValueExact() : null;
    }

    private static String nullSafeBigInt(BigInteger value) {
        return value != null ? value.toString() : null;
    }
}
