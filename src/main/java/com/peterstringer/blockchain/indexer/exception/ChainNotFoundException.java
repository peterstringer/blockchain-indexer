package com.peterstringer.blockchain.indexer.exception;

/**
 * Thrown when an API operation references a chain that is not present
 * in the application configuration.
 *
 * <p>Mapped to HTTP 404 by the global exception handler.
 */
public class ChainNotFoundException extends RuntimeException {

    private final String chain;

    public ChainNotFoundException(String chain) {
        super("Chain not found: " + chain);
        this.chain = chain;
    }

    public String getChain() {
        return chain;
    }
}
