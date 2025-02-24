// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.sync;

import com.swirlds.common.platform.NodeId;
import java.util.List;
import org.hiero.consensus.gossip.FallenBehindManager;

public class TestingSyncManager implements FallenBehindManager {
    /** whether we have fallen behind or not */
    private boolean fallenBehind = false;

    @Override
    public void reportFallenBehind(NodeId id) {
        // for testing, we conclude we have fallen behind even if just 1 node says so
        fallenBehind = true;
    }

    @Override
    public void resetFallenBehind() {
        fallenBehind = false;
    }

    @Override
    public boolean hasFallenBehind() {
        return fallenBehind;
    }

    @Override
    public List<NodeId> getNeighborsForReconnect() {
        return null;
    }

    @Override
    public List<NodeId> getNeededForFallenBehind() {
        return null;
    }

    @Override
    public int numReportedFallenBehind() {
        return 0;
    }

    @Override
    public boolean shouldReconnectFrom(final NodeId peerId) {
        return false;
    }
}
