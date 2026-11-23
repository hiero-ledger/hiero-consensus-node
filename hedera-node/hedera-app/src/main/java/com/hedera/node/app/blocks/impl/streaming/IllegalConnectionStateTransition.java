// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Indicates that a block node connection's state attempted to transition to an invalid state given its current state.
 */
public class IllegalConnectionStateTransition extends RuntimeException {

    public IllegalConnectionStateTransition(
            @NonNull final ConnectionId connectionId,
            @NonNull final ConnectionState currentState,
            @NonNull final ConnectionState wantedState) {
        super("Attempted to update connection (" + connectionId + ") state from " + currentState + " to "
                + wantedState);
    }
}
