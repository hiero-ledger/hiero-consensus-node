// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Helper class for JMH benchmarks to access package-private methods in BlockNodeConnection.
 *
 * <p>This helper exists because benchmarks need to manually create and activate connections
 * outside the normal {@link BlockNodeConnectionManager} lifecycle. In production, the manager
 * is responsible for connection state transitions.
 *
 * <p>This class is intentionally placed in the same package to access package-private methods
 * while keeping the production API surface minimal.
 */
public final class BlockNodeConnectionHelper {

    private BlockNodeConnectionHelper() {
        // Utility class
    }

    /**
     * Updates the connection state of a BlockNodeConnection.
     *
     * <p><b>Note:</b> This should only be used in benchmarks where connections are manually
     * created outside the ConnectionManager's control.
     *
     * @param connection the connection to update
     * @param newState the new state to transition to
     */
    public static void updateConnectionState(
            @NonNull final BlockNodeConnection connection, @NonNull final ConnectionState newState) {
        connection.updateConnectionState(newState);
    }
}
