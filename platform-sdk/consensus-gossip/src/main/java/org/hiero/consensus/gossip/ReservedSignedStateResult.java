// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip;

import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Represents the result of a reconnect operation, containing either a reserved signed state or an exception.
 * This is used as the resource type for the {@link org.hiero.base.concurrent.BlockingResourceProvider}
 * that manages access to reconnect states.
 */
public record ReservedSignedStateResult(
        @Nullable ReservedSignedState reservedSignedState,
        @Nullable RuntimeException throwable) implements AutoCloseable {
    @Override
    public void close() {
        if (reservedSignedState != null) {
            reservedSignedState.close();
        }
    }

    /**
     * Check if this result represents an error.
     *
     * @return true if this result contains an exception and no state, false otherwise
     */
    public boolean isError() {
        return this.reservedSignedState() == null && this.throwable() != null;
    }
}
