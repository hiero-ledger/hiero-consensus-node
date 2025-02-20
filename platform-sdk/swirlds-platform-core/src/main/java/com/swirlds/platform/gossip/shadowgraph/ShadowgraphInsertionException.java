// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

/**
 * An exception thrown by {@link Shadowgraph} when an event cannot be added to the shadow graph.
 */
public class ShadowgraphInsertionException extends RuntimeException {

    private final InsertableStatus status;

    /**
     * Constructs a new runtime exception with the specified detail message. The cause is not initialized, and may
     * subsequently be initialized by a call to {@link #initCause}.
     *
     * @param message
     * 		the detail message. The detail message is saved for later retrieval by the {@link #getMessage()} method.
     * @param status
     * 		the status of the event insertion
     */
    public ShadowgraphInsertionException(final String message, final InsertableStatus status) {
        super(message);
        this.status = status;
    }

    /**
     * The status of the event which prevented its insertion into the shadow graph.
     *
     * @return the status
     */
    public InsertableStatus getStatus() {
        return status;
    }
}
