package com.peterstringer.blockchain.indexer.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.core.methods.response.EthBlock;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IndexedBlock}, covering the builder, the
 * {@code fromWeb3jBlock} factory method, null handling for optional
 * fields, and gas percentage calculation.
 */
@DisplayName("IndexedBlock")
class IndexedBlockTest {

    // =========================================================================
    // Builder tests
    // =========================================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build a block with all required fields")
        void buildsWithAllFields() {
            Instant now = Instant.now();
            IndexedBlock block = IndexedBlock.builder()
                    .chain("ethereum")
                    .chainId(1L)
                    .blockNumber(18_000_000L)
                    .blockHash("0xabc")
                    .parentHash("0xdef")
                    .timestamp(1_700_000_000L)
                    .indexedAt(now)
                    .miner("0x1234")
                    .gasLimit(30_000_000L)
                    .gasUsed(15_000_000L)
                    .transactionCount(200)
                    .build();

            assertThat(block.getChain()).isEqualTo("ethereum");
            assertThat(block.getChainId()).isEqualTo(1L);
            assertThat(block.getBlockNumber()).isEqualTo(18_000_000L);
            assertThat(block.getBlockHash()).isEqualTo("0xabc");
            assertThat(block.getParentHash()).isEqualTo("0xdef");
            assertThat(block.getTimestamp()).isEqualTo(1_700_000_000L);
            assertThat(block.getIndexedAt()).isEqualTo(now);
            assertThat(block.getMiner()).isEqualTo("0x1234");
            assertThat(block.getGasLimit()).isEqualTo(30_000_000L);
            assertThat(block.getGasUsed()).isEqualTo(15_000_000L);
            assertThat(block.getTransactionCount()).isEqualTo(200);
        }

        @Test
        @DisplayName("should default transactions to empty list")
        void defaultsTransactionsToEmptyList() {
            IndexedBlock block = IndexedBlock.builder()
                    .chain("polygon")
                    .build();

            assertThat(block.getTransactions()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("should allow nullable gas pricing fields for pre-EIP-1559 blocks")
        void allowsNullGasPricing() {
            IndexedBlock block = IndexedBlock.builder()
                    .chain("ethereum")
                    .baseFeePerGas(null)
                    .avgGasPrice(null)
                    .medianGasPrice(null)
                    .maxGasPrice(null)
                    .minGasPrice(null)
                    .build();

            assertThat(block.getBaseFeePerGas()).isNull();
            assertThat(block.getAvgGasPrice()).isNull();
            assertThat(block.getMedianGasPrice()).isNull();
            assertThat(block.getMaxGasPrice()).isNull();
            assertThat(block.getMinGasPrice()).isNull();
        }
    }

    // =========================================================================
    // fromWeb3jBlock factory method
    // =========================================================================

    @Nested
    @DisplayName("fromWeb3jBlock")
    class FromWeb3jBlockTests {

        @Test
        @DisplayName("should correctly convert a Web3j block with transactions")
        void convertsBlockWithTransactions() {
            EthBlock.Block web3jBlock = createMockBlock(
                    18_000_000L,
                    "0xblockhash",
                    "0xparenthash",
                    1_700_000_000L,
                    "0xminer",
                    30_000_000L,
                    25_000_000L,
                    BigInteger.valueOf(50_000_000_000L), // baseFee 50 gwei
                    List.of(
                            createMockTx(BigInteger.valueOf(60_000_000_000L), BigInteger.valueOf(1_000_000_000_000_000_000L)),
                            createMockTx(BigInteger.valueOf(80_000_000_000L), BigInteger.valueOf(2_000_000_000_000_000_000L)),
                            createMockTx(BigInteger.valueOf(70_000_000_000L), BigInteger.valueOf(500_000_000_000_000_000L))
                    )
            );

            IndexedBlock result = IndexedBlock.fromWeb3jBlock("ethereum", 1L, web3jBlock);

            assertThat(result.getChain()).isEqualTo("ethereum");
            assertThat(result.getChainId()).isEqualTo(1L);
            assertThat(result.getBlockNumber()).isEqualTo(18_000_000L);
            assertThat(result.getBlockHash()).isEqualTo("0xblockhash");
            assertThat(result.getParentHash()).isEqualTo("0xparenthash");
            assertThat(result.getTimestamp()).isEqualTo(1_700_000_000L);
            assertThat(result.getMiner()).isEqualTo("0xminer");
            assertThat(result.getGasLimit()).isEqualTo(30_000_000L);
            assertThat(result.getGasUsed()).isEqualTo(25_000_000L);
            assertThat(result.getBaseFeePerGas()).isEqualTo(50_000_000_000L);
            assertThat(result.getTransactionCount()).isEqualTo(3);
            assertThat(result.getIndexedAt()).isNotNull();
            assertThat(result.getTransactions()).isEmpty(); // transactions not populated by factory
        }

        @Test
        @DisplayName("should compute gas used percentage correctly")
        void computesGasPercentage() {
            EthBlock.Block web3jBlock = createMockBlock(
                    100L, "0xa", "0xb", 1_000L, "0xm",
                    30_000_000L, 15_000_000L,
                    null, List.of()
            );

            IndexedBlock result = IndexedBlock.fromWeb3jBlock("test", 1L, web3jBlock);

            assertThat(result.getGasUsedPercentage()).isCloseTo(50.0, within(0.01));
        }

        @Test
        @DisplayName("should compute gas price statistics from transactions")
        void computesGasPriceStats() {
            EthBlock.Block web3jBlock = createMockBlock(
                    100L, "0xa", "0xb", 1_000L, "0xm",
                    30_000_000L, 20_000_000L,
                    null,
                    List.of(
                            createMockTx(BigInteger.valueOf(10_000_000_000L), BigInteger.ZERO),
                            createMockTx(BigInteger.valueOf(30_000_000_000L), BigInteger.ZERO),
                            createMockTx(BigInteger.valueOf(20_000_000_000L), BigInteger.ZERO),
                            createMockTx(BigInteger.valueOf(40_000_000_000L), BigInteger.ZERO)
                    )
            );

            IndexedBlock result = IndexedBlock.fromWeb3jBlock("test", 1L, web3jBlock);

            // avg = (10+30+20+40)/4 = 25 gwei
            assertThat(result.getAvgGasPrice()).isEqualTo(25_000_000_000L);
            assertThat(result.getMinGasPrice()).isEqualTo(10_000_000_000L);
            assertThat(result.getMaxGasPrice()).isEqualTo(40_000_000_000L);
            // median of [10,20,30,40] → sorted[2] = 30 gwei
            assertThat(result.getMedianGasPrice()).isEqualTo(30_000_000_000L);
        }

        @Test
        @DisplayName("should handle block with no transactions")
        void handlesEmptyTransactions() {
            EthBlock.Block web3jBlock = createMockBlock(
                    100L, "0xa", "0xb", 1_000L, "0xm",
                    30_000_000L, 0L, null, List.of()
            );

            IndexedBlock result = IndexedBlock.fromWeb3jBlock("test", 1L, web3jBlock);

            assertThat(result.getTransactionCount()).isEqualTo(0);
            assertThat(result.getAvgGasPrice()).isNull();
            assertThat(result.getMinGasPrice()).isNull();
            assertThat(result.getMaxGasPrice()).isNull();
            assertThat(result.getMedianGasPrice()).isNull();
            assertThat(result.getTotalValue()).isEqualTo("0");
        }

        @Test
        @DisplayName("should handle null baseFeePerGas for pre-EIP-1559 blocks")
        void handlesNullBaseFee() {
            EthBlock.Block web3jBlock = createMockBlock(
                    100L, "0xa", "0xb", 1_000L, "0xm",
                    30_000_000L, 15_000_000L, null, List.of()
            );

            IndexedBlock result = IndexedBlock.fromWeb3jBlock("test", 1L, web3jBlock);

            assertThat(result.getBaseFeePerGas()).isNull();
        }

        @Test
        @DisplayName("should handle null difficulty and totalDifficulty for PoS blocks")
        void handlesNullDifficulty() {
            EthBlock.Block web3jBlock = createMockBlock(
                    100L, "0xa", "0xb", 1_000L, "0xm",
                    30_000_000L, 0L, null, List.of()
            );
            when(web3jBlock.getDifficulty()).thenReturn(null);
            when(web3jBlock.getTotalDifficulty()).thenReturn(null);

            IndexedBlock result = IndexedBlock.fromWeb3jBlock("test", 1L, web3jBlock);

            assertThat(result.getDifficulty()).isNull();
            assertThat(result.getTotalDifficulty()).isNull();
        }

        @Test
        @DisplayName("should aggregate total value from all transactions")
        void aggregatesTotalValue() {
            EthBlock.Block web3jBlock = createMockBlock(
                    100L, "0xa", "0xb", 1_000L, "0xm",
                    30_000_000L, 20_000_000L, null,
                    List.of(
                            createMockTx(BigInteger.valueOf(50_000_000_000L),
                                    new BigInteger("1000000000000000000")), // 1 ETH
                            createMockTx(BigInteger.valueOf(60_000_000_000L),
                                    new BigInteger("2000000000000000000"))  // 2 ETH
                    )
            );

            IndexedBlock result = IndexedBlock.fromWeb3jBlock("test", 1L, web3jBlock);

            // 1 ETH + 2 ETH = 3 ETH in wei
            assertThat(result.getTotalValue()).isEqualTo("3000000000000000000");
        }

        @Test
        @DisplayName("should handle null gasUsed gracefully")
        void handlesNullGasUsed() {
            EthBlock.Block web3jBlock = createMockBlock(
                    100L, "0xa", "0xb", 1_000L, "0xm",
                    30_000_000L, null, null, List.of()
            );

            IndexedBlock result = IndexedBlock.fromWeb3jBlock("test", 1L, web3jBlock);

            assertThat(result.getGasUsed()).isNull();
            assertThat(result.getGasUsedPercentage()).isNull();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static EthBlock.Block createMockBlock(long blockNumber, String hash,
                                                   String parentHash, long timestamp,
                                                   String miner, long gasLimit,
                                                   Long gasUsed, BigInteger baseFee,
                                                   List<EthBlock.TransactionResult<?>> txs) {
        EthBlock.Block block = mock(EthBlock.Block.class);
        when(block.getNumber()).thenReturn(BigInteger.valueOf(blockNumber));
        when(block.getHash()).thenReturn(hash);
        when(block.getParentHash()).thenReturn(parentHash);
        when(block.getTimestamp()).thenReturn(BigInteger.valueOf(timestamp));
        when(block.getMiner()).thenReturn(miner);
        when(block.getGasLimit()).thenReturn(BigInteger.valueOf(gasLimit));
        when(block.getGasUsed()).thenReturn(gasUsed != null ? BigInteger.valueOf(gasUsed) : null);
        when(block.getBaseFeePerGas()).thenReturn(baseFee);
        when(block.getDifficulty()).thenReturn(BigInteger.ZERO);
        when(block.getTotalDifficulty()).thenReturn(BigInteger.ZERO);
        when(block.getSize()).thenReturn(BigInteger.valueOf(50_000));
        when(block.getExtraData()).thenReturn("0x");
        when(block.getLogsBloom()).thenReturn("0x00");
        when(block.getNonce()).thenReturn(BigInteger.ZERO);
        when(block.getMixHash()).thenReturn("0x00");
        when((List<EthBlock.TransactionResult<?>>) (List<?>) block.getTransactions()).thenReturn(txs);
        return block;
    }

    private static EthBlock.TransactionResult<?> createMockTx(BigInteger gasPrice, BigInteger value) {
        EthBlock.TransactionObject txObj = mock(EthBlock.TransactionObject.class);
        when(txObj.getGasPrice()).thenReturn(gasPrice);
        when(txObj.getValue()).thenReturn(value);

        @SuppressWarnings("unchecked")
        EthBlock.TransactionResult<EthBlock.TransactionObject> txResult = mock(EthBlock.TransactionResult.class);
        when(txResult.get()).thenReturn(txObj);
        return txResult;
    }
}
