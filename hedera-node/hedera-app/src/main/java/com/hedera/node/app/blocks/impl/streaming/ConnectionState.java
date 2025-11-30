// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Enum denoting the possible states of a block node connection.
 */
public enum ConnectionState {

    /**
     * The connection is not yet established, but may at a later time.
     */
    UNINITIALIZED(0, false),

    /**
     * The connection has been established, but it isn't actively being used yet.
     */
    READY(1, false),

    /**
     * The connection has been established and is actively being used.
     */
    ACTIVE(2, false),

    /**
     * The connection is in the process of being closed. Only cleanup related operations are permitted.
     */
    CLOSING(3, true),

    /**
     * The connection is closed. No more operations should happen on this connection. This is a terminal state.
     */
    CLOSED(4, true);

    /**
     * Numerical representation of this state as a "step" in the connection lifecycle. The connection lifecycle should
     * only move forward and this value allows us to assign an order across all the states.
     */
    private final int lifecycleStep;

    /**
     * Is this state a terminal state? (i.e. closed/closing)
     */
    private final boolean isTerminal;

    ConnectionState(final int lifecycleStep, final boolean isTerminal) {
        this.lifecycleStep = lifecycleStep;
        this.isTerminal = isTerminal;
    }

    /**
     * @return true if the state represents a terminal or end-state for the connection lifecycle, else false
     */
    public boolean isTerminal() {
        return isTerminal;
    }

    /**
     * Checks if this status is permitted to transition to the specified status.
     *
     * @param newStatus the status to transition to
     * @return true if this status can transition to the new status, else false
     */
    public boolean canTransitionTo(@NonNull final ConnectionState newStatus) {
        requireNonNull(newStatus, "newStatus is required");

        /*
        We only want to allow a "forward" transition (e.g. READY to ACTIVE) and prevent moving "backwards" in the
        connection's lifecycle (e.g. ACTIVE to READY).
         */

        return lifecycleStep <= newStatus.lifecycleStep;
    }
}
