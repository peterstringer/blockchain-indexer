package com.peterstringer.blockchain.indexer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;

/**
 * Normalized representation of an on-chain transaction with receipt data.
 *
 * <p>Combines data from both the transaction object and its receipt to provide
 * a single, denormalized view suitable for analytics and Parquet export.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>{@code value} is stored as {@link String} because ether values can
 *       exceed {@code Long.MAX_VALUE} (which caps at ~9.2 ETH in wei).</li>
 *   <li>Gas prices are stored as {@code Long} in wei — safe for all current
 *       EVM chains where gas prices stay well below 2^63.</li>
 *   <li>{@code to} is null for contract-creation transactions; in that case
 *       {@code contractAddress} is populated from the receipt.</li>
 *   <li>{@code type} follows EIP-2718: 0 = legacy, 1 = access list, 2 = EIP-1559.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexedTransaction {

    // ---- Chain identification ----
    private String chain;
    private Long chainId;

    // ---- Transaction identity ----
    private String hash;
    private Long blockNumber;
    private String blockHash;
    private Integer transactionIndex;

    // ---- Participants ----
    private String from;
    /** Null for contract-creation transactions. */
    private String to;
    /** Populated from receipt when {@code to} is null (contract creation). */
    private String contractAddress;

    // ---- Value ----
    /** Wei value as String to safely represent amounts exceeding Long.MAX_VALUE. */
    private String value;

    // ---- Gas ----
    private Long gas;
    private Long gasPrice;
    /** Null for legacy (type 0) and access-list (type 1) transactions. */
    private Long maxFeePerGas;
    /** Null for legacy (type 0) and access-list (type 1) transactions. */
    private Long maxPriorityFeePerGas;
    /** Actual gas price paid after EIP-1559 settlement; from receipt. */
    private Long effectiveGasPrice;
    /** Actual gas consumed; from receipt. */
    private Long gasUsed;

    // ---- Type ----
    /** EIP-2718 transaction type: 0=legacy, 1=access list, 2=EIP-1559. */
    private Integer type;

    // ---- Status ----
    private boolean success;
    /** Raw status string from receipt ("0x1" = success, "0x0" = revert). */
    private String status;

    // ---- Data ----
    private String input;
    private Integer inputLength;
    private Long nonce;

    // ---- Signature ----
    private Long v;
    private String r;
    private String s;

    /**
     * Builds an {@code IndexedTransaction} by merging a web3j {@link Transaction}
     * with its corresponding {@link TransactionReceipt}.
     *
     * @param chainName human-readable chain name (e.g. "ethereum")
     * @param chainId   EIP-155 chain ID
     * @param tx        the raw transaction from the JSON-RPC provider
     * @param receipt   the transaction receipt (must not be null)
     * @return a fully populated {@code IndexedTransaction}
     */
    public static IndexedTransaction fromWeb3jTransaction(String chainName, Long chainId,
                                                          Transaction tx, TransactionReceipt receipt) {
        String inputData = tx.getInput();

        return IndexedTransaction.builder()
                .chain(chainName)
                .chainId(chainId)
                .hash(tx.getHash())
                .blockNumber(tx.getBlockNumber().longValueExact())
                .blockHash(tx.getBlockHash())
                .transactionIndex(tx.getTransactionIndex().intValueExact())
                .from(tx.getFrom())
                .to(tx.getTo())
                .contractAddress(receipt.getContractAddress())
                .value(tx.getValue().toString())
                .gas(tx.getGas().longValueExact())
                .gasPrice(safeLongValue(tx.getGasPrice()))
                .maxFeePerGas(safeLongValue(tx.getMaxFeePerGas()))
                .maxPriorityFeePerGas(safeLongValue(tx.getMaxPriorityFeePerGas()))
                .effectiveGasPrice(decodeHexLong(receipt.getEffectiveGasPrice()))
                .gasUsed(receipt.getGasUsed().longValueExact())
                .type(tx.getType() != null ? Integer.decode(tx.getType()) : 0)
                .success("0x1".equals(receipt.getStatus()))
                .status(receipt.getStatus())
                .input(inputData)
                .inputLength(inputData != null ? inputData.length() : 0)
                .nonce(tx.getNonce().longValueExact())
                .v(tx.getV())
                .r(tx.getR())
                .s(tx.getS())
                .build();
    }

    private static Long safeLongValue(BigInteger value) {
        return value != null ? value.longValueExact() : null;
    }

    private static Long decodeHexLong(String hexValue) {
        if (hexValue == null || hexValue.isEmpty()) {
            return null;
        }
        return new BigInteger(hexValue.substring(2), 16).longValueExact();
    }
}
