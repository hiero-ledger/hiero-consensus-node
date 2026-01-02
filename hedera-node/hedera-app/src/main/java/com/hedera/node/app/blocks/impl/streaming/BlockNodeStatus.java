// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

/**
 * Result of a status check for a specific Block Node.
 *
 * @param wasReachable true if the Block Node was reachable, else false
 * @param latencyMillis the duration the status check took to complete, or -1 if unreachable
 * @param latestBlockAvailable the latest block available on the Block Node, or -1 if unreachable
 */
public record BlockNodeStatus(boolean wasReachable, long latencyMillis, long latestBlockAvailable) {

    private static final BlockNodeStatus NOT_REACHABLE = new BlockNodeStatus(false, -1L, -1L);

    /**
     * Convenience method for creating a status of unreachable.
     *
     * @return a status marked as unreachable
     */
    public static BlockNodeStatus notReachable() {
        return NOT_REACHABLE;
    }

    /**
     * Convenience method for creating a status of reachable.
     *
     * @param latencyMillis the latency (in milliseconds) the status check took
     * @param latestBlockAvailable the latest block available
     * @return a status marked as reachable
     */
    public static BlockNodeStatus reachable(final long latencyMillis, final long latestBlockAvailable) {
        return new BlockNodeStatus(true, latencyMillis, latestBlockAvailable);
    }
}
