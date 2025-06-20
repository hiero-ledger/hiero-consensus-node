// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

public class ReconnectFinishPayload extends AbstractLogPayload {

    private boolean receiving;
    private long nodeId;
    private long otherNodeId;
    private long round;
    private boolean success;

    public ReconnectFinishPayload() {}

    /**
     * @param message
     * 		a human readable message
     * @param receiving
     * 		if true then this node is the receiver, i.e. it is the one attempting to reconnect.
     * 		If false then this node is the sender and is helping another node to reconnect.
     * @param nodeId
     * 		this node's ID
     * @param otherNodeId
     * 		the other node's ID
     * @param round
     * 		the round of the reconnected state
     */
    public ReconnectFinishPayload(
            final String message,
            final boolean receiving,
            final long nodeId,
            final long otherNodeId,
            final long round) {
        super(message);
        this.receiving = receiving;
        this.nodeId = nodeId;
        this.otherNodeId = otherNodeId;
        this.round = round;
    }

    public boolean isReceiving() {
        return receiving;
    }

    public void setReceiving(boolean receiving) {
        this.receiving = receiving;
    }

    public long getNodeId() {
        return nodeId;
    }

    public void setNodeId(int nodeId) {
        this.nodeId = nodeId;
    }

    public long getOtherNodeId() {
        return otherNodeId;
    }

    public void setOtherNodeId(int otherNodeId) {
        this.otherNodeId = otherNodeId;
    }

    public long getRound() {
        return round;
    }

    public void setRound(long round) {
        this.round = round;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(final boolean success) {
        this.success = success;
    }
}
