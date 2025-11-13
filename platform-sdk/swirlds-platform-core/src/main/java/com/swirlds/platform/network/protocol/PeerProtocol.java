// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import com.swirlds.platform.network.Connection;

/**
 * A network protocol that run over a provided connection. The decision to run the protocol is made outside it, it can
 * only communicate its willingness to run through the provided interface. An instance of this class must be created per
 * peer.
 */
public interface PeerProtocol extends ProtocolRunnable {

    /**
     * Used to ask the protocol if we should initiate it. If this method returns true, one of two things will always
     * subsequently happen:
     * <ul>
     *     <li>The initiate will be successful and {@link #runProtocol(Connection)} will be called</li>
     *     <li>The initiate will fail and {@link #initiateFailed()} will be called</li>
     * </ul>
     *
     * @return true iff we should we try and initiate this protocol with our peer
     */
    boolean shouldInitiate();

    /**
     * If this protocol returns true for {@link #shouldInitiate()} but negotiation fails prior to
     * {@link #runProtocol(Connection)} being called then this method is invoked.
     */
    default void initiateFailed() {
        // Override if needed
    }

    /**
     * Our peer initiated this protocol, should we accept? If this method returns true, one of two things will always
     * subsequently happen:
     *
     * <ul>
     *     <li>The initiate will be successful and {@link #runProtocol(Connection)} will be called</li>
     *     <li>The initiate will fail and {@link #acceptFailed()} ()} will be called</li>
     * </ul>
     *
     * @return true if we should accept, false if we should reject
     */
    boolean shouldAccept();

    /**
     * If this protocol returns true for {@link #shouldAccept()} but negotiation fails prior to
     * {@link #runProtocol(Connection)} being called then this method is invoked.
     */
    default void acceptFailed() {
        // Override if needed
    }

    /**
     * <p>
     * If both sides initiated this protocol simultaneously, should we proceed with running the protocol?
     * </p>
     * <p>
     * IMPORTANT: the value returned should remain consistent for a protocol, it should never change depending on the
     * state of the instance.
     * </p>
     *
     * @return true if we should run, false otherwise
     */
    boolean acceptOnSimultaneousInitiate();

    /**
     * Will be called after connection is broken, to clean up possible internal resources if needed. Default
     * implementation is no-op
     */
    default void cleanup() {}

    /** @return a string name representing this protocol */
    default String getProtocolName() {
        return this.getClass().getSimpleName();
    }
}
