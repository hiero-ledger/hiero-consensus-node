// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged when a node falls behind but is unable to reconnect.
 */
public class UnableToReconnectPayload extends AbstractLogPayload {

    private long nodeId;

    public UnableToReconnectPayload() {}

    public UnableToReconnectPayload(final String message, final long nodeId) {
        super(message);
        this.nodeId = nodeId;
    }

    public long getNodeId() {
        return nodeId;
    }

    public void setNodeId(int nodeId) {
        this.nodeId = nodeId;
    }
}
