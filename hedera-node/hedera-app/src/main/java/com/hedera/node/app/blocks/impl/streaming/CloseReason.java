// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

/**
 * Enumeration of possible reasons why a block node connection was closed.
 */
public enum CloseReason {
    /**
     * The connection was closed because the block node is behind the consensus node.
     */
    BLOCK_NODE_BEHIND(true),
    /**
     * The connection was closed because there was elevated latency communicating with the block node.
     */
    BLOCK_NODE_HIGH_LATENCY(true),
    /**
     * The connection was closed because block buffer saturation is increasing from the lack of blocks being
     * acknowledged fast enough.
     */
    BUFFER_SATURATION(true),
    /**
     * The connection was closed because there is a new block node configuration.
     */
    CONFIG_UPDATE(false),
    /**
     * The connection was closed because there was an error encountered on the connection.
     */
    CONNECTION_ERROR(true),
    /**
     * The connection was closed because it was determined to be stalled.
     */
    CONNECTION_STALLED(true),
    /**
     * The connection was closed because an end stream response was received from the block node.
     */
    END_STREAM_RECEIVED(true),
    /**
     * The connection was closed because another block node with higher priority is eligible for streaming and it is
     * preferred over the existing connection.
     */
    HIGHER_PRIORITY_FOUND(false),
    /**
     * The connection was closed because an internal error was encountered.
     */
    INTERNAL_ERROR(true),
    /**
     * The connection was closed because the connection was reset due to being established for too long.
     */
    PERIODIC_RESET(false),
    /**
     * The connection was closed because a newer connection preempted it.
     */
    NEW_CONNECTION(false),
    /**
     * The connection was closed because block node communications have been shut down on the consensus node.
     */
    SHUTDOWN(false),
    /**
     * The connection was closed for an unknown reason. Spooky.
     */
    UNKNOWN(false);

    private final boolean isCoolDownRequired;

    CloseReason(final boolean isCoolDownRequired) {
        this.isCoolDownRequired = isCoolDownRequired;
    }

    /**
     * Returns whether the close reason requires the block node to enter a state of cool down before being streamed to
     * again. This cool down is to prevent reconnecting to a block node that is not in an ideal state. For example, if
     * a block node is too far behind, we want to prevent reconnecting to the block node for a while to give it a chance
     * to catch up from other block nodes.
     *
     * @return true if the close reason should cause the block node to enter a cool down period, else false
     */
    public boolean isCoolDownRequired() {
        return isCoolDownRequired;
    }
}
