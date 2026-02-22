package com.peterstringer.blockchain.indexer.service;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import com.peterstringer.blockchain.indexer.model.IndexedBlock;
import com.peterstringer.blockchain.indexer.model.IndexedTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/**
 * Generates realistic synthetic blockchain data for demo and testing purposes.
 *
 * <p>Activated only when {@code indexer.demo.enabled=true}. All generated data is
 * deterministic — the same {@code seed + blockNumber} always produces identical
 * output, making test assertions stable and debugging reproducible.
 *
 * <h2>Synthetic Data Patterns</h2>
 * <p>The generator models real-world Ethereum mainnet behavior:
 *
 * <h3>Block timing</h3>
 * <p>~12-second intervals matching Ethereum's proof-of-stake slot cadence,
 * with ±2 second jitter for realism.
 *
 * <h3>Gas economics</h3>
 * <ul>
 *   <li><b>Gas limit:</b> Fixed at 30,000,000 (current Ethereum target).</li>
 *   <li><b>Gas utilization:</b> 60–95% fill rate, reflecting typical mainnet load.</li>
 *   <li><b>Base fee:</b> 20–100 gwei with a sinusoidal daily cycle — higher during
 *       peak hours (14:00–22:00 UTC) mimicking US/EU business overlap. Weekend
 *       activity is reduced by ~30%.</li>
 *   <li><b>Congestion spikes:</b> ~5% of blocks experience 2× gas prices,
 *       simulating NFT mints or DeFi events.</li>
 * </ul>
 *
 * <h3>Transaction mix</h3>
 * <ul>
 *   <li>70% EIP-1559 (type 2) — the dominant modern format.</li>
 *   <li>20% Legacy (type 0) — backward-compatible transfers.</li>
 *   <li>10% Contract creation — {@code to} is null, contract address is set.</li>
 * </ul>
 *
 * <h3>Value distribution</h3>
 * <p>Follows a Pareto-like pattern: most transactions carry small amounts
 * (0.001–1 ETH), with occasional large transfers (10–1000 ETH) in ~5% of
 * transactions. This mirrors real on-chain value flow.
 *
 * <h3>Why these patterns matter</h3>
 * <p>Realistic synthetic data ensures that:
 * <ul>
 *   <li>Parquet compression ratios approximate production workloads.</li>
 *   <li>Gas price analytics (avg, median, percentiles) produce meaningful charts.</li>
 *   <li>Concurrency and batch processing are stressed under representative load.</li>
 *   <li>Frontend dashboards display visually credible data during demos.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "indexer.demo.enabled", havingValue = "true")
public class SyntheticDataProvider {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataProvider.class);

    // ---- Ethereum constants ----
    private static final long GAS_LIMIT = 30_000_000L;
    private static final long GWEI = 1_000_000_000L;
    private static final long ETH_IN_WEI = 1_000_000_000_000_000_000L;

    // ---- Simulated miner pool ----
    private static final String[] MINERS = {
            "0x1f9090aaE28b8a3dCeaDf281B0F12828e676c326",  // rsync-builder
            "0x95222290DD7278Aa3Ddd389Cc1E1d165CC4BAfe5",  // beaverbuild
            "0x4838B106FCe9647Bdf1E7877BF73cE8B0BAD5f97",  // Titan Builder
            "0xDAFEA492D9c6733ae3d56b7Ed1ADB60692c98Bc5",  // Flashbots
            "0x388C818CA8B9251b393131C08a736A67ccB19297",  // Lido
            "0xeBec795c9c8bBD61FFc14A6662944748F299cAcf",  // bloXroute
            "0x690B9A9E9aa1C9dB991C7721a92d351Db4FaC990",  // builder0x69
            "0x0000000000000000000000000000000000000000",  // null (for PoS)
            "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",  // synthetic-1
            "0x7a250d5630B4cF539739dF2C5dAcb4c659F2488D",  // synthetic-2
    };

    // ---- Transaction type weights ----
    private static final double EIP1559_WEIGHT = 0.70;
    private static final double LEGACY_WEIGHT = 0.90;  // cumulative: 70% EIP-1559 + 20% legacy

    private final IndexerProperties properties;

    public SyntheticDataProvider(IndexerProperties properties) {
        this.properties = properties;
        log.info("SyntheticDataProvider enabled — demo mode active (seed={})",
                properties.getDemo().getSeed());
    }

    /**
     * Generates a synthetic block with transactions for the given chain and block number.
     *
     * <p>The output is deterministic: identical inputs always produce identical output.
     *
     * @param chainKey    the chain config key (e.g. "ethereum")
     * @param blockNumber the block number to generate
     * @return a fully populated {@link IndexedBlock} with embedded transactions
     */
    public IndexedBlock generateBlock(String chainKey, long blockNumber) {
        IndexerProperties.ChainConfig chainConfig = properties.getChains().get(chainKey);
        String chainName = chainConfig.getName();
        long chainId = chainConfig.getChainId();
        long seed = properties.getDemo().getSeed();

        Random rng = new Random(seed ^ blockNumber ^ chainId);

        // ---- Block header ----
        // Use per-chain block time with ±10% jitter for realistic inter-block intervals
        long blockTimeMs = chainConfig.getBlockTimeMs();
        long blockTimeSec = blockTimeMs / 1000;
        long baseTimestampSec;
        if (blockTimeSec > 0) {
            baseTimestampSec = 1_700_000_000L + (blockNumber * blockTimeSec);
        } else {
            // Sub-second chains (e.g. Arbitrum 250ms): compute from millis, convert to seconds
            long baseTimestampMs = 1_700_000_000_000L + (blockNumber * blockTimeMs);
            baseTimestampSec = baseTimestampMs / 1000;
        }
        // ±10% jitter relative to block time
        long jitterMs = (long) ((rng.nextDouble() - 0.5) * 0.20 * blockTimeMs);
        long jitterSec = jitterMs / 1000;
        long timestampSec = baseTimestampSec + jitterSec;
        long timestamp = timestampSec * 1000L; // epoch millis for storage

        String blockHash = randomHash(rng);
        String parentHash = randomHash(new Random(seed ^ (blockNumber - 1) ^ chainId));

        // ---- Gas economics ----
        double fillRate = 0.60 + (rng.nextDouble() * 0.35);
        long gasUsed = (long) (GAS_LIMIT * fillRate);

        long baseFee = computeBaseFee(timestampSec, rng);
        boolean isCongested = rng.nextDouble() < 0.05;
        if (isCongested) {
            baseFee *= 2;
        }

        // ---- Transaction count ----
        int txCount = 100 + rng.nextInt(201); // 100-300

        // ---- Generate transactions ----
        List<IndexedTransaction> transactions = new ArrayList<>(txCount);
        BigInteger totalValue = BigInteger.ZERO;
        long gasPriceSum = 0;
        long maxGasPrice = 0;
        long minGasPrice = Long.MAX_VALUE;
        List<Long> allGasPrices = new ArrayList<>(txCount);

        for (int i = 0; i < txCount; i++) {
            IndexedTransaction tx = generateTransaction(
                    chainName, chainId, blockNumber, blockHash, timestamp, i, baseFee, rng);
            transactions.add(tx);

            long txGasPrice = tx.getGasPrice() != null ? tx.getGasPrice() : baseFee;
            gasPriceSum += txGasPrice;
            maxGasPrice = Math.max(maxGasPrice, txGasPrice);
            minGasPrice = Math.min(minGasPrice, txGasPrice);
            allGasPrices.add(txGasPrice);

            totalValue = totalValue.add(new BigInteger(tx.getValue()));
        }

        long avgGasPrice = txCount > 0 ? gasPriceSum / txCount : 0;
        allGasPrices.sort(Long::compareTo);
        long medianGasPrice = txCount > 0 ? allGasPrices.get(txCount / 2) : 0;
        double gasUsedPct = (gasUsed * 100.0) / GAS_LIMIT;

        String miner = MINERS[rng.nextInt(MINERS.length)];

        IndexedBlock block = IndexedBlock.builder()
                .chain(chainName)
                .chainId(chainId)
                .blockNumber(blockNumber)
                .blockHash(blockHash)
                .parentHash(parentHash)
                .timestamp(timestamp)
                .indexedAt(Instant.now())
                .miner(miner)
                .difficulty("0")
                .totalDifficulty("0")
                .size(50_000L + rng.nextLong(200_000))
                .gasLimit(GAS_LIMIT)
                .gasUsed(gasUsed)
                .gasUsedPercentage(gasUsedPct)
                .baseFeePerGas(baseFee)
                .avgGasPrice(avgGasPrice)
                .medianGasPrice(medianGasPrice)
                .maxGasPrice(maxGasPrice)
                .minGasPrice(minGasPrice == Long.MAX_VALUE ? 0 : minGasPrice)
                .transactionCount(txCount)
                .totalValue(totalValue.toString())
                .extraData("0x" + HexFormat.of().formatHex(randomBytes(rng, 32)))
                .logsBloom("0x" + "0".repeat(512))
                .nonce("0x0000000000000000")
                .mixHash(randomHash(rng))
                .transactions(transactions)
                .build();

        log.debug("Generated synthetic block {} for chain={}: {} txs, baseFee={} gwei, gasUsed={}%",
                blockNumber, chainName, txCount, baseFee / GWEI, String.format("%.1f", gasUsedPct));

        return block;
    }

    /**
     * Returns the synthetic chain head: startBlock + syntheticBlockCount - 1.
     *
     * @param chainKey the chain config key
     * @return the highest block number available in demo mode
     */
    public long getLatestBlockNumber(String chainKey) {
        IndexerProperties.ChainConfig config = properties.getChains().get(chainKey);
        return config.getStartBlock() + properties.getDemo().getSyntheticBlockCount() - 1;
    }

    // =========================================================================
    // Transaction generation
    // =========================================================================

    private IndexedTransaction generateTransaction(String chainName, long chainId,
                                                   long blockNumber, String blockHash,
                                                   long blockTimestamp, int txIndex,
                                                   long baseFee, Random rng) {
        double typeRoll = rng.nextDouble();
        int type;
        boolean isContractCreation;

        if (typeRoll < EIP1559_WEIGHT) {
            type = 2;   // EIP-1559
            isContractCreation = false;
        } else if (typeRoll < LEGACY_WEIGHT) {
            type = 0;   // Legacy
            isContractCreation = false;
        } else {
            type = 2;   // Contract creation (also EIP-1559)
            isContractCreation = true;
        }

        // ---- Gas pricing ----
        long priorityFee = (1 + rng.nextLong(5)) * GWEI;
        long gasPrice;
        Long maxFeePerGas = null;
        Long maxPriorityFeePerGas = null;

        if (type == 2) {
            maxPriorityFeePerGas = priorityFee;
            maxFeePerGas = baseFee + priorityFee + rng.nextLong(10) * GWEI;
            gasPrice = baseFee + priorityFee;
        } else {
            gasPrice = baseFee + priorityFee + rng.nextLong(5) * GWEI;
        }

        long effectiveGasPrice = gasPrice;

        // ---- Gas ----
        long gasLimit;
        long gasUsed;
        if (isContractCreation) {
            gasLimit = 500_000 + rng.nextLong(4_500_000);
            gasUsed = (long) (gasLimit * (0.70 + rng.nextDouble() * 0.25));
        } else {
            gasLimit = 21_000 + rng.nextLong(300_000);
            gasUsed = (long) (gasLimit * (0.50 + rng.nextDouble() * 0.50));
        }

        // ---- Value ----
        BigInteger value;
        if (isContractCreation) {
            value = BigInteger.ZERO;
        } else if (rng.nextDouble() < 0.05) {
            // ~5%: large transfer (10-1000 ETH)
            long ethAmount = 10 + rng.nextLong(991);
            value = BigInteger.valueOf(ethAmount).multiply(BigInteger.valueOf(ETH_IN_WEI));
        } else {
            // ~95%: small transfer (0.001 - 1 ETH)
            long milliEth = 1 + rng.nextLong(1000);
            value = BigInteger.valueOf(milliEth).multiply(BigInteger.valueOf(ETH_IN_WEI / 1000));
        }

        // ---- Status ----
        boolean success = rng.nextDouble() < 0.98;

        // ---- Input data ----
        String input;
        int inputLength;
        if (isContractCreation) {
            byte[] bytecode = randomBytes(rng, 200 + rng.nextInt(2000));
            input = "0x" + HexFormat.of().formatHex(bytecode);
            inputLength = input.length();
        } else if (rng.nextDouble() < 0.60) {
            // 60% contract calls: 4-byte selector + params
            byte[] calldata = randomBytes(rng, 4 + 32 * (1 + rng.nextInt(4)));
            input = "0x" + HexFormat.of().formatHex(calldata);
            inputLength = input.length();
        } else {
            input = "0x";
            inputLength = 2;
        }

        String from = randomAddress(rng);
        String to = isContractCreation ? null : randomAddress(rng);
        String contractAddress = isContractCreation ? randomAddress(rng) : null;

        long nonce = rng.nextLong(0, 10_000);
        long v = 27 + rng.nextInt(2);

        return IndexedTransaction.builder()
                .chain(chainName)
                .chainId(chainId)
                .hash(randomHash(rng))
                .blockNumber(blockNumber)
                .blockHash(blockHash)
                .transactionIndex(txIndex)
                .from(from)
                .to(to)
                .contractAddress(contractAddress)
                .value(value.toString())
                .gas(gasLimit)
                .gasPrice(gasPrice)
                .maxFeePerGas(maxFeePerGas)
                .maxPriorityFeePerGas(maxPriorityFeePerGas)
                .effectiveGasPrice(effectiveGasPrice)
                .gasUsed(gasUsed)
                .type(type)
                .success(success)
                .status(success ? "0x1" : "0x0")
                .input(input)
                .inputLength(inputLength)
                .nonce(nonce)
                .v(v)
                .r("0x" + HexFormat.of().formatHex(randomBytes(rng, 32)))
                .s("0x" + HexFormat.of().formatHex(randomBytes(rng, 32)))
                .build();
    }

    // =========================================================================
    // Gas fee model
    // =========================================================================

    /**
     * Computes a realistic base fee in wei using time-of-day and day-of-week patterns.
     *
     * <p>Models a sinusoidal daily cycle with peak at 18:00 UTC (US/EU overlap)
     * and a trough at 06:00 UTC. Weekend activity is reduced by ~30%.
     */
    private long computeBaseFee(long epochSeconds, Random rng) {
        // Seconds since midnight UTC
        long secondsInDay = epochSeconds % 86_400;
        double hourOfDay = secondsInDay / 3600.0;

        // Day of week (0=Thu for epoch, rough approximation is fine for synthetic data)
        long daysSinceEpoch = epochSeconds / 86_400;
        int dayOfWeek = (int) ((daysSinceEpoch + 4) % 7); // 0=Sun, 6=Sat
        boolean isWeekend = (dayOfWeek == 0 || dayOfWeek == 6);

        // Sinusoidal cycle: peak at 18:00 UTC, trough at 06:00 UTC
        double cycleFactor = 0.5 + 0.5 * Math.sin(2 * Math.PI * (hourOfDay - 6.0) / 24.0);

        // Base: 20-100 gwei range
        double baseGwei = 20 + cycleFactor * 80;
        if (isWeekend) {
            baseGwei *= 0.70;
        }

        // Random noise: ±15%
        double noise = 1.0 + (rng.nextDouble() - 0.5) * 0.30;
        baseGwei *= noise;

        return (long) (baseGwei * GWEI);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String randomHash(Random rng) {
        return "0x" + HexFormat.of().formatHex(randomBytes(rng, 32));
    }

    private static String randomAddress(Random rng) {
        return "0x" + HexFormat.of().formatHex(randomBytes(rng, 20));
    }

    private static byte[] randomBytes(Random rng, int length) {
        byte[] bytes = new byte[length];
        rng.nextBytes(bytes);
        return bytes;
    }
}
