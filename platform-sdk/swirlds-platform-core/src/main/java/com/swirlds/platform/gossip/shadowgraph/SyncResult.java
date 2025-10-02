// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import org.hiero.consensus.model.node.NodeId;

/**
 * Information about a successful sync that just occurred
 */
public class SyncResult {
    private final NodeId otherId;
    private final int eventsRead;
    private final int eventsWritten;

    /**
     * @param otherId
     * 		the ID of the node we synced with
     * @param eventsRead
     * 		the number of events read during the sync
     * @param eventsWritten
     * 		the number of events written during the sync
     */
    public SyncResult(final NodeId otherId, final int eventsRead, final int eventsWritten) {
        this.otherId = otherId;
        this.eventsRead = eventsRead;
        this.eventsWritten = eventsWritten;
    }

    /**
     * @return the ID of the node we synced with
     */
    public NodeId getOtherId() {
        return otherId;
    }

    /**
     * @return the number of events read during the sync
     */
    public int getEventsRead() {
        return eventsRead;
    }

    /**
     * @return the number of events written during the sync
     */
    public int getEventsWritten() {
        return eventsWritten;
    }
}
