// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Enumeration of possible reasons why a block node connection was closed.
 */
public enum CloseReason {
    /**
     * The connection was closed because the block node is behind the consensus node.
     */
    BLOCK_NODE_BEHIND(CoolDownType.BASIC),
    /**
     * The connection was closed because there was elevated latency communicating with the block node.
     */
    BLOCK_NODE_HIGH_LATENCY(CoolDownType.BASIC),
    /**
     * The connection was closed because block buffer saturation is increasing from the lack of blocks being
     * acknowledged fast enough.
     */
    BUFFER_SATURATION(CoolDownType.BASIC),
    /**
     * The connection was closed because there is a new block node configuration.
     */
    CONFIG_UPDATE(CoolDownType.NONE),
    /**
     * The connection was closed because there was an error encountered on the connection.
     */
    CONNECTION_ERROR(CoolDownType.BASIC),
    /**
     * The connection was closed because it was determined to be stalled.
     */
    CONNECTION_STALLED(CoolDownType.BASIC),
    /**
     * The connection was closed due to receiving an end stream response AND as a result the associated block node is
     * considered degraded because several end stream responses have been received in a short period of time.
     */
    TOO_MANY_END_STREAM_RESPONSES(CoolDownType.EXTENDED),
    /**
     * The connection was closed because an end stream response was received from the block node.
     */
    END_STREAM_RECEIVED(CoolDownType.BASIC),
    /**
     * A transient end stream response was received. Transient end stream responses are treated as momentary blips that
     * don't impact the overall streaming eligibility of the associated block node, unless multiple are received.
     */
    TRANSIENT_END_STREAM_RECEIVED(CoolDownType.NONE),
    /**
     * The connection was closed because another block node with higher priority is eligible for streaming and it is
     * preferred over the existing connection.
     */
    HIGHER_PRIORITY_FOUND(CoolDownType.NONE),
    /**
     * The connection was closed because an internal error was encountered.
     */
    INTERNAL_ERROR(CoolDownType.BASIC),
    /**
     * The connection was closed because the connection was reset due to being established for too long.
     */
    PERIODIC_RESET(CoolDownType.NONE),
    /**
     * The connection was closed because a newer connection preempted it.
     */
    NEW_CONNECTION(CoolDownType.NONE),
    /**
     * The connection was closed because block node communications have been shut down on the consensus node.
     */
    SHUTDOWN(CoolDownType.NONE),
    /**
     * The connection was closed for an unknown reason. Spooky.
     */
    UNKNOWN(CoolDownType.NONE);

    private final CoolDownType coolDownType;

    CloseReason(final CoolDownType coolDownType) {
        this.coolDownType = coolDownType;
    }

    /**
     * Returns whether this close reason is considered deviant. In general, a deviant close reason signals a fatal
     * problem and thus will cause the associated block node to enter a cool down period.
     *
     * @return true if the associated block node should have a cool down applied, else false
     */
    public boolean isDeviantCloseReason() {
        return coolDownType != CoolDownType.NONE;
    }

    /**
     * Returns the type of cool down that should be applied given the close reason.
     *
     * @return the cool down associated with this close reason
     */
    public @NonNull CoolDownType coolDownType() {
        return coolDownType;
    }
}
