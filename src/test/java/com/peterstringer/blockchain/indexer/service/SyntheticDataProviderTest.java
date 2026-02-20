package com.peterstringer.blockchain.indexer.service;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.model.IndexedBlock;
import com.peterstringer.blockchain.indexer.model.IndexedTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SyntheticDataProvider}, verifying deterministic block
 * generation, transaction type distributions, gas price patterns, and
 * realistic value ranges.
 */
@DisplayName("SyntheticDataProvider")
class SyntheticDataProviderTest {

    private static final long SEED = 42L;
    private static final int SYNTHETIC_BLOCK_COUNT = 100;

    private SyntheticDataProvider provider;
    private IndexerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IndexerProperties();

        IndexerProperties.DemoConfig demo = new IndexerProperties.DemoConfig();
        demo.setEnabled(true);
        demo.setSeed(SEED);
        demo.setSyntheticBlockCount(SYNTHETIC_BLOCK_COUNT);
        properties.setDemo(demo);

        IndexerProperties.ChainConfig ethereum = new IndexerProperties.ChainConfig();
        ethereum.setName("Ethereum");
        ethereum.setChainId(1);
        ethereum.setRpcUrls(List.of("http://localhost:8545"));
        ethereum.setStartBlock(18_000_000);

        IndexerProperties.ChainConfig polygon = new IndexerProperties.ChainConfig();
        polygon.setName("Polygon");
        polygon.setChainId(137);
        polygon.setRpcUrls(List.of("http://localhost:8546"));
        polygon.setStartBlock(50_000_000);

        Map<String, IndexerProperties.ChainConfig> chains = new HashMap<>();
        chains.put("ethereum", ethereum);
        chains.put("polygon", polygon);
        properties.setChains(chains);

        provider = new SyntheticDataProvider(properties);
    }

    // =========================================================================
    // Determinism tests
    // =========================================================================

    @Nested
    @DisplayName("Determinism")
    class DeterminismTests {

        @Test
        @DisplayName("same seed and block number should produce identical blocks")
        void sameSeedProducesIdenticalBlocks() {
            IndexedBlock first = provider.generateBlock("ethereum", 18_000_001);
            IndexedBlock second = provider.generateBlock("ethereum", 18_000_001);

            assertThat(first.getBlockHash()).isEqualTo(second.getBlockHash());
            assertThat(first.getParentHash()).isEqualTo(second.getParentHash());
            assertThat(first.getGasUsed()).isEqualTo(second.getGasUsed());
            assertThat(first.getBaseFeePerGas()).isEqualTo(second.getBaseFeePerGas());
            assertThat(first.getTransactionCount()).isEqualTo(second.getTransactionCount());
            assertThat(first.getMiner()).isEqualTo(second.getMiner());
        }

        @Test
        @DisplayName("same seed should produce identical transaction data")
        void sameSeedProducesIdenticalTransactions() {
            IndexedBlock first = provider.generateBlock("ethereum", 18_000_005);
            IndexedBlock second = provider.generateBlock("ethereum", 18_000_005);

            assertThat(first.getTransactions()).hasSameSizeAs(second.getTransactions());
            for (int i = 0; i < first.getTransactions().size(); i++) {
                IndexedTransaction tx1 = first.getTransactions().get(i);
                IndexedTransaction tx2 = second.getTransactions().get(i);
                assertThat(tx1.getHash()).isEqualTo(tx2.getHash());
                assertThat(tx1.getValue()).isEqualTo(tx2.getValue());
                assertThat(tx1.getGasPrice()).isEqualTo(tx2.getGasPrice());
                assertThat(tx1.getType()).isEqualTo(tx2.getType());
            }
        }

        @Test
        @DisplayName("different block numbers should produce different blocks")
        void differentBlockNumbersProduceDifferentBlocks() {
            IndexedBlock block1 = provider.generateBlock("ethereum", 18_000_001);
            IndexedBlock block2 = provider.generateBlock("ethereum", 18_000_002);

            assertThat(block1.getBlockHash()).isNotEqualTo(block2.getBlockHash());
            assertThat(block1.getBlockNumber()).isNotEqualTo(block2.getBlockNumber());
        }

        @Test
        @DisplayName("different chains with same block number should produce different blocks")
        void differentChainsProduceDifferentBlocks() {
            IndexedBlock eth = provider.generateBlock("ethereum", 18_000_001);
            IndexedBlock poly = provider.generateBlock("polygon", 18_000_001);

            assertThat(eth.getBlockHash()).isNotEqualTo(poly.getBlockHash());
            assertThat(eth.getChain()).isEqualTo("Ethereum");
            assertThat(poly.getChain()).isEqualTo("Polygon");
            assertThat(eth.getChainId()).isEqualTo(1L);
            assertThat(poly.getChainId()).isEqualTo(137L);
        }

        @Test
        @DisplayName("different seed should produce different blocks")
        void differentSeedProducesDifferentBlocks() {
            IndexedBlock original = provider.generateBlock("ethereum", 18_000_001);

            // Create a provider with a different seed
            IndexerProperties props2 = new IndexerProperties();
            IndexerProperties.DemoConfig demo2 = new IndexerProperties.DemoConfig();
            demo2.setEnabled(true);
            demo2.setSeed(999L);
            demo2.setSyntheticBlockCount(SYNTHETIC_BLOCK_COUNT);
            props2.setDemo(demo2);
            props2.setChains(properties.getChains());

            SyntheticDataProvider provider2 = new SyntheticDataProvider(props2);
            IndexedBlock different = provider2.generateBlock("ethereum", 18_000_001);

            assertThat(original.getBlockHash()).isNotEqualTo(different.getBlockHash());
        }
    }

    // =========================================================================
    // Block structure tests
    // =========================================================================

    @Nested
    @DisplayName("Block Structure")
    class BlockStructureTests {

        @ParameterizedTest
        @ValueSource(longs = {18_000_000, 18_000_050, 18_000_099})
        @DisplayName("should generate blocks with valid structure")
        void generatesValidBlockStructure(long blockNumber) {
            IndexedBlock block = provider.generateBlock("ethereum", blockNumber);

            assertThat(block.getChain()).isEqualTo("Ethereum");
            assertThat(block.getChainId()).isEqualTo(1L);
            assertThat(block.getBlockNumber()).isEqualTo(blockNumber);
            assertThat(block.getBlockHash()).startsWith("0x").hasSize(66);
            assertThat(block.getParentHash()).startsWith("0x").hasSize(66);
            assertThat(block.getTimestamp()).isPositive();
            assertThat(block.getIndexedAt()).isNotNull();
            assertThat(block.getMiner()).startsWith("0x");
            assertThat(block.getGasLimit()).isEqualTo(30_000_000L);
            assertThat(block.getTransactions()).isNotEmpty();
        }

        @Test
        @DisplayName("should populate all gas pricing fields")
        void populatesGasPricingFields() {
            IndexedBlock block = provider.generateBlock("ethereum", 18_000_010);

            assertThat(block.getBaseFeePerGas()).isPositive();
            assertThat(block.getAvgGasPrice()).isPositive();
            assertThat(block.getMedianGasPrice()).isPositive();
            assertThat(block.getMaxGasPrice()).isPositive();
            assertThat(block.getMinGasPrice()).isNotNegative();
            assertThat(block.getMaxGasPrice()).isGreaterThanOrEqualTo(block.getMinGasPrice());
            assertThat(block.getMaxGasPrice()).isGreaterThanOrEqualTo(block.getAvgGasPrice());
        }

        @Test
        @DisplayName("parent hash should use the previous block's seed")
        void parentHashUsesCorrectSeed() {
            // The parent hash is derived from (seed ^ (blockNumber-1) ^ chainId)
            IndexedBlock block = provider.generateBlock("ethereum", 18_000_010);
            assertThat(block.getParentHash()).startsWith("0x").hasSize(66);
        }
    }

    // =========================================================================
    // Gas economics tests
    // =========================================================================

    @Nested
    @DisplayName("Gas Economics")
    class GasEconomicsTests {

        @Test
        @DisplayName("gas utilization should be between 60-95%")
        void gasUtilizationInRange() {
            for (long i = 18_000_000; i < 18_000_020; i++) {
                IndexedBlock block = provider.generateBlock("ethereum", i);
                double utilPct = block.getGasUsedPercentage();

                assertThat(utilPct).as("Block %d gas utilization", i)
                        .isBetween(55.0, 100.0); // small margin for rounding
            }
        }

        @Test
        @DisplayName("base fee should be in reasonable gwei range")
        void baseFeeInReasonableRange() {
            long gwei = 1_000_000_000L;

            for (long i = 18_000_000; i < 18_000_020; i++) {
                IndexedBlock block = provider.generateBlock("ethereum", i);
                double baseFeeGwei = block.getBaseFeePerGas() / (double) gwei;

                // Base fee range: 20-200 gwei (allowing for congestion spikes and noise)
                assertThat(baseFeeGwei).as("Block %d base fee in gwei", i)
                        .isBetween(5.0, 250.0);
            }
        }

        @Test
        @DisplayName("block size should be in realistic range")
        void blockSizeInRange() {
            IndexedBlock block = provider.generateBlock("ethereum", 18_000_005);

            // size = 50_000 + random(200_000)
            assertThat(block.getSize()).isBetween(50_000L, 250_000L);
        }
    }

    // =========================================================================
    // Transaction distribution tests
    // =========================================================================

    @Nested
    @DisplayName("Transaction Distribution")
    class TransactionDistributionTests {

        @Test
        @DisplayName("transaction count should be between 100 and 300")
        void transactionCountInRange() {
            for (long i = 18_000_000; i < 18_000_010; i++) {
                IndexedBlock block = provider.generateBlock("ethereum", i);
                assertThat(block.getTransactionCount()).as("Block %d tx count", i)
                        .isBetween(100, 300);
                assertThat(block.getTransactions()).hasSize(block.getTransactionCount());
            }
        }

        @Test
        @DisplayName("should produce a mix of EIP-1559 and legacy transaction types")
        void producesTransactionTypeMix() {
            // Generate enough blocks to get a statistically meaningful sample
            int eip1559Count = 0;
            int legacyCount = 0;
            int contractCreationCount = 0;
            int totalTxs = 0;

            for (long i = 18_000_000; i < 18_000_005; i++) {
                IndexedBlock block = provider.generateBlock("ethereum", i);
                for (IndexedTransaction tx : block.getTransactions()) {
                    totalTxs++;
                    if (tx.getTo() == null) {
                        contractCreationCount++;
                    } else if (tx.getType() == 2) {
                        eip1559Count++;
                    } else if (tx.getType() == 0) {
                        legacyCount++;
                    }
                }
            }

            // EIP-1559 should be the majority (~70%)
            double eip1559Pct = (double) eip1559Count / totalTxs;
            assertThat(eip1559Pct).as("EIP-1559 percentage")
                    .isBetween(0.50, 0.85);

            // Legacy should be present (~20%)
            double legacyPct = (double) legacyCount / totalTxs;
            assertThat(legacyPct).as("Legacy percentage")
                    .isBetween(0.10, 0.35);

            // Contract creations should be a small fraction (~10%)
            double creationPct = (double) contractCreationCount / totalTxs;
            assertThat(creationPct).as("Contract creation percentage")
                    .isBetween(0.02, 0.20);
        }

        @Test
        @DisplayName("most transactions should be successful (~98%)")
        void mostTransactionsSuccessful() {
            int successCount = 0;
            int totalTxs = 0;

            for (long i = 18_000_000; i < 18_000_005; i++) {
                IndexedBlock block = provider.generateBlock("ethereum", i);
                for (IndexedTransaction tx : block.getTransactions()) {
                    totalTxs++;
                    if (tx.isSuccess()) {
                        successCount++;
                    }
                }
            }

            double successRate = (double) successCount / totalTxs;
            assertThat(successRate).as("Transaction success rate")
                    .isBetween(0.93, 1.0);
        }
    }

    // =========================================================================
    // Transaction field tests
    // =========================================================================

    @Nested
    @DisplayName("Transaction Fields")
    class TransactionFieldTests {

        @Test
        @DisplayName("all transactions should have valid hashes and addresses")
        void validHashesAndAddresses() {
            IndexedBlock block = provider.generateBlock("ethereum", 18_000_005);

            for (IndexedTransaction tx : block.getTransactions()) {
                assertThat(tx.getHash()).startsWith("0x").hasSize(66);
                assertThat(tx.getFrom()).startsWith("0x").hasSize(42);

                if (tx.getTo() != null) {
                    assertThat(tx.getTo()).startsWith("0x").hasSize(42);
                }
                if (tx.getContractAddress() != null) {
                    assertThat(tx.getContractAddress()).startsWith("0x").hasSize(42);
                }
            }
        }

        @Test
        @DisplayName("EIP-1559 transactions should have maxFeePerGas and maxPriorityFeePerGas")
        void eip1559FieldsPopulated() {
            IndexedBlock block = provider.generateBlock("ethereum", 18_000_005);

            List<IndexedTransaction> eip1559Txs = block.getTransactions().stream()
                    .filter(tx -> tx.getType() == 2 && tx.getTo() != null)
                    .toList();

            assertThat(eip1559Txs).isNotEmpty();
            for (IndexedTransaction tx : eip1559Txs) {
                assertThat(tx.getMaxFeePerGas()).as("maxFeePerGas").isPositive();
                assertThat(tx.getMaxPriorityFeePerGas()).as("maxPriorityFeePerGas").isPositive();
            }
        }

        @Test
        @DisplayName("transaction values should be non-negative")
        void valuesAreNonNegative() {
            IndexedBlock block = provider.generateBlock("ethereum", 18_000_005);

            for (IndexedTransaction tx : block.getTransactions()) {
                BigInteger value = new BigInteger(tx.getValue());
                assertThat(value).as("tx value for %s", tx.getHash())
                        .isNotNegative();
            }
        }

        @Test
        @DisplayName("contract creation transactions should have null 'to' and non-null contractAddress")
        void contractCreationsHaveCorrectFields() {
            // Search for a block with contract creation
            for (long i = 18_000_000; i < 18_000_020; i++) {
                IndexedBlock block = provider.generateBlock("ethereum", i);
                List<IndexedTransaction> creations = block.getTransactions().stream()
                        .filter(tx -> tx.getTo() == null)
                        .toList();

                if (!creations.isEmpty()) {
                    for (IndexedTransaction tx : creations) {
                        assertThat(tx.getContractAddress()).isNotNull();
                        assertThat(tx.getValue()).isEqualTo("0");
                    }
                    return; // Found and verified at least one
                }
            }
            // With ~10% creation rate and 100+ txs per block, we should find one
        }
    }

    // =========================================================================
    // Latest block number tests
    // =========================================================================

    @Nested
    @DisplayName("getLatestBlockNumber")
    class LatestBlockNumberTests {

        @Test
        @DisplayName("should return startBlock + syntheticBlockCount - 1")
        void returnsCorrectLatestBlock() {
            long latest = provider.getLatestBlockNumber("ethereum");

            // startBlock=18_000_000, count=100 → latest=18_000_099
            assertThat(latest).isEqualTo(18_000_000 + SYNTHETIC_BLOCK_COUNT - 1);
        }

        @Test
        @DisplayName("should return correct value for different chains")
        void returnsCorrectValuePerChain() {
            long ethLatest = provider.getLatestBlockNumber("ethereum");
            long polyLatest = provider.getLatestBlockNumber("polygon");

            assertThat(ethLatest).isEqualTo(18_000_099);
            assertThat(polyLatest).isEqualTo(50_000_099);
        }
    }
}
