package com.peterstringer.blockchain.indexer.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IndexedTransaction}, covering the builder and
 * the {@code fromWeb3jTransaction} factory method for different
 * transaction types (legacy, EIP-1559, contract creation).
 */
@DisplayName("IndexedTransaction")
class IndexedTransactionTest {

    // =========================================================================
    // Builder tests
    // =========================================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build a transaction with all fields")
        void buildsWithAllFields() {
            IndexedTransaction tx = IndexedTransaction.builder()
                    .chain("ethereum")
                    .chainId(1L)
                    .hash("0xtxhash")
                    .blockNumber(18_000_000L)
                    .blockHash("0xblockhash")
                    .transactionIndex(5)
                    .from("0xsender")
                    .to("0xrecipient")
                    .value("1000000000000000000")
                    .gas(21_000L)
                    .gasPrice(50_000_000_000L)
                    .type(0)
                    .success(true)
                    .status("0x1")
                    .input("0x")
                    .inputLength(2)
                    .nonce(42L)
                    .build();

            assertThat(tx.getChain()).isEqualTo("ethereum");
            assertThat(tx.getHash()).isEqualTo("0xtxhash");
            assertThat(tx.getFrom()).isEqualTo("0xsender");
            assertThat(tx.getTo()).isEqualTo("0xrecipient");
            assertThat(tx.getValue()).isEqualTo("1000000000000000000");
            assertThat(tx.getGas()).isEqualTo(21_000L);
            assertThat(tx.getType()).isEqualTo(0);
            assertThat(tx.isSuccess()).isTrue();
            assertThat(tx.getNonce()).isEqualTo(42L);
        }

        @Test
        @DisplayName("should allow null 'to' for contract creation")
        void allowsNullTo() {
            IndexedTransaction tx = IndexedTransaction.builder()
                    .chain("ethereum")
                    .to(null)
                    .contractAddress("0xcontract")
                    .build();

            assertThat(tx.getTo()).isNull();
            assertThat(tx.getContractAddress()).isEqualTo("0xcontract");
        }

        @Test
        @DisplayName("should allow null EIP-1559 fields for legacy transactions")
        void allowsNullEip1559Fields() {
            IndexedTransaction tx = IndexedTransaction.builder()
                    .chain("ethereum")
                    .type(0)
                    .maxFeePerGas(null)
                    .maxPriorityFeePerGas(null)
                    .build();

            assertThat(tx.getMaxFeePerGas()).isNull();
            assertThat(tx.getMaxPriorityFeePerGas()).isNull();
        }
    }

    // =========================================================================
    // fromWeb3jTransaction factory method
    // =========================================================================

    @Nested
    @DisplayName("fromWeb3jTransaction")
    class FromWeb3jTransactionTests {

        @Test
        @DisplayName("should convert a legacy (type 0) transaction")
        void convertsLegacyTransaction() {
            Transaction tx = createMockTransaction(
                    "0xtxhash", 18_000_000L, "0xblockhash", 3,
                    "0xfrom", "0xto", "1000000000000000000",
                    21_000L, 50_000_000_000L,
                    null, null,  // no EIP-1559 fields
                    "0x0",       // type
                    42L, 27L
            );
            TransactionReceipt receipt = createMockReceipt(
                    "0x1", "0x" + Long.toHexString(50_000_000_000L),
                    21_000L, null
            );

            IndexedTransaction result = IndexedTransaction.fromWeb3jTransaction(
                    "ethereum", 1L, tx, receipt);

            assertThat(result.getChain()).isEqualTo("ethereum");
            assertThat(result.getChainId()).isEqualTo(1L);
            assertThat(result.getHash()).isEqualTo("0xtxhash");
            assertThat(result.getBlockNumber()).isEqualTo(18_000_000L);
            assertThat(result.getTransactionIndex()).isEqualTo(3);
            assertThat(result.getFrom()).isEqualTo("0xfrom");
            assertThat(result.getTo()).isEqualTo("0xto");
            assertThat(result.getValue()).isEqualTo("1000000000000000000");
            assertThat(result.getGas()).isEqualTo(21_000L);
            assertThat(result.getGasPrice()).isEqualTo(50_000_000_000L);
            assertThat(result.getType()).isEqualTo(0);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getStatus()).isEqualTo("0x1");
            assertThat(result.getMaxFeePerGas()).isNull();
            assertThat(result.getMaxPriorityFeePerGas()).isNull();
            assertThat(result.getNonce()).isEqualTo(42L);
        }

        @Test
        @DisplayName("should convert an EIP-1559 (type 2) transaction")
        void convertsEip1559Transaction() {
            Transaction tx = createMockTransaction(
                    "0xtxhash", 18_000_000L, "0xblockhash", 0,
                    "0xfrom", "0xto", "500000000000000000",
                    100_000L, 60_000_000_000L,
                    BigInteger.valueOf(100_000_000_000L),  // maxFeePerGas
                    BigInteger.valueOf(2_000_000_000L),    // maxPriorityFeePerGas
                    "0x2",                                  // type
                    10L, 28L
            );
            TransactionReceipt receipt = createMockReceipt(
                    "0x1", "0x" + Long.toHexString(55_000_000_000L),
                    65_000L, null
            );

            IndexedTransaction result = IndexedTransaction.fromWeb3jTransaction(
                    "ethereum", 1L, tx, receipt);

            assertThat(result.getType()).isEqualTo(2);
            assertThat(result.getMaxFeePerGas()).isEqualTo(100_000_000_000L);
            assertThat(result.getMaxPriorityFeePerGas()).isEqualTo(2_000_000_000L);
            assertThat(result.getEffectiveGasPrice()).isEqualTo(55_000_000_000L);
            assertThat(result.getGasUsed()).isEqualTo(65_000L);
        }

        @Test
        @DisplayName("should handle contract creation (null 'to', contract address in receipt)")
        void handlesContractCreation() {
            Transaction tx = createMockTransaction(
                    "0xtxhash", 18_000_000L, "0xblockhash", 0,
                    "0xfrom", null, "0",
                    500_000L, 50_000_000_000L,
                    null, null, "0x0", 5L, 27L
            );
            TransactionReceipt receipt = createMockReceipt(
                    "0x1", null, 400_000L, "0xnewcontract"
            );

            IndexedTransaction result = IndexedTransaction.fromWeb3jTransaction(
                    "ethereum", 1L, tx, receipt);

            assertThat(result.getTo()).isNull();
            assertThat(result.getContractAddress()).isEqualTo("0xnewcontract");
        }

        @Test
        @DisplayName("should mark failed transactions correctly")
        void handlesFailedTransaction() {
            Transaction tx = createMockTransaction(
                    "0xtxhash", 18_000_000L, "0xblockhash", 0,
                    "0xfrom", "0xto", "0",
                    21_000L, 50_000_000_000L,
                    null, null, "0x0", 0L, 27L
            );
            TransactionReceipt receipt = createMockReceipt(
                    "0x0", null, 21_000L, null  // status 0x0 = reverted
            );

            IndexedTransaction result = IndexedTransaction.fromWeb3jTransaction(
                    "ethereum", 1L, tx, receipt);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getStatus()).isEqualTo("0x0");
        }

        @Test
        @DisplayName("should compute input length from input data")
        void computesInputLength() {
            Transaction tx = createMockTransaction(
                    "0xtxhash", 18_000_000L, "0xblockhash", 0,
                    "0xfrom", "0xto", "0",
                    50_000L, 50_000_000_000L,
                    null, null, "0x0", 0L, 27L
            );
            // Simulate a contract call: 4-byte selector + 32-byte param (72 hex chars + 0x prefix)
            when(tx.getInput()).thenReturn("0xa9059cbb" + "0".repeat(64));

            TransactionReceipt receipt = createMockReceipt("0x1", null, 40_000L, null);

            IndexedTransaction result = IndexedTransaction.fromWeb3jTransaction(
                    "ethereum", 1L, tx, receipt);

            assertThat(result.getInput()).startsWith("0xa9059cbb");
            assertThat(result.getInputLength()).isEqualTo(result.getInput().length());
        }

        @Test
        @DisplayName("should handle null input data gracefully")
        void handlesNullInput() {
            Transaction tx = createMockTransaction(
                    "0xtxhash", 18_000_000L, "0xblockhash", 0,
                    "0xfrom", "0xto", "0",
                    21_000L, 50_000_000_000L,
                    null, null, "0x0", 0L, 27L
            );
            when(tx.getInput()).thenReturn(null);

            TransactionReceipt receipt = createMockReceipt("0x1", null, 21_000L, null);

            IndexedTransaction result = IndexedTransaction.fromWeb3jTransaction(
                    "ethereum", 1L, tx, receipt);

            assertThat(result.getInputLength()).isEqualTo(0);
        }

        @Test
        @DisplayName("should handle null type field as legacy (type 0)")
        void handlesNullType() {
            Transaction tx = createMockTransaction(
                    "0xtxhash", 18_000_000L, "0xblockhash", 0,
                    "0xfrom", "0xto", "0",
                    21_000L, 50_000_000_000L,
                    null, null, null, // null type
                    0L, 27L
            );

            TransactionReceipt receipt = createMockReceipt("0x1", null, 21_000L, null);

            IndexedTransaction result = IndexedTransaction.fromWeb3jTransaction(
                    "ethereum", 1L, tx, receipt);

            assertThat(result.getType()).isEqualTo(0);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Transaction createMockTransaction(String hash, long blockNumber,
                                                      String blockHash, int txIndex,
                                                      String from, String to, String value,
                                                      long gas, long gasPrice,
                                                      BigInteger maxFeePerGas,
                                                      BigInteger maxPriorityFeePerGas,
                                                      String type, long nonce, long v) {
        Transaction tx = mock(Transaction.class);
        when(tx.getHash()).thenReturn(hash);
        when(tx.getBlockNumber()).thenReturn(BigInteger.valueOf(blockNumber));
        when(tx.getBlockHash()).thenReturn(blockHash);
        when(tx.getTransactionIndex()).thenReturn(BigInteger.valueOf(txIndex));
        when(tx.getFrom()).thenReturn(from);
        when(tx.getTo()).thenReturn(to);
        when(tx.getValue()).thenReturn(new BigInteger(value));
        when(tx.getGas()).thenReturn(BigInteger.valueOf(gas));
        when(tx.getGasPrice()).thenReturn(BigInteger.valueOf(gasPrice));
        when(tx.getMaxFeePerGas()).thenReturn(maxFeePerGas);
        when(tx.getMaxPriorityFeePerGas()).thenReturn(maxPriorityFeePerGas);
        when(tx.getType()).thenReturn(type);
        when(tx.getInput()).thenReturn("0x");
        when(tx.getNonce()).thenReturn(BigInteger.valueOf(nonce));
        when(tx.getV()).thenReturn(v);
        when(tx.getR()).thenReturn("0x1234");
        when(tx.getS()).thenReturn("0x5678");
        return tx;
    }

    private static TransactionReceipt createMockReceipt(String status,
                                                         String effectiveGasPrice,
                                                         long gasUsed,
                                                         String contractAddress) {
        TransactionReceipt receipt = mock(TransactionReceipt.class);
        when(receipt.getStatus()).thenReturn(status);
        when(receipt.getEffectiveGasPrice()).thenReturn(effectiveGasPrice);
        when(receipt.getGasUsed()).thenReturn(BigInteger.valueOf(gasUsed));
        when(receipt.getContractAddress()).thenReturn(contractAddress);
        return receipt;
    }
}
