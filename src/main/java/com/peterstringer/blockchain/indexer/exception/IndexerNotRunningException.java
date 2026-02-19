package com.peterstringer.blockchain.indexer.exception;

/**
 * Thrown when an operation requires the indexer to be running for a
 * specific chain, but it is currently stopped.
 *
 * <p>Mapped to HTTP 409 Conflict by the global exception handler.
 */
public class IndexerNotRunningException extends RuntimeException {

    private final String chain;

    public IndexerNotRunningException(String chain) {
        super("Indexer is not running for chain: " + chain);
        this.chain = chain;
    }

    public String getChain() {
        return chain;
    }
}
