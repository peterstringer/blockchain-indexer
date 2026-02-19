package com.peterstringer.blockchain.indexer.exception;

/**
 * Thrown when an attempt is made to start indexing for a chain that is
 * already actively being indexed.
 *
 * <p>Mapped to HTTP 409 Conflict by the global exception handler.
 */
public class IndexerAlreadyRunningException extends RuntimeException {

    private final String chain;

    public IndexerAlreadyRunningException(String chain) {
        super("Indexer is already running for chain: " + chain);
        this.chain = chain;
    }

    public String getChain() {
        return chain;
    }
}
