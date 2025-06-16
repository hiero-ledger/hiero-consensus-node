// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.sync;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.node.NodeId;

public class TestingSyncManager implements FallenBehindManager {
    /** whether we have fallen behind or not */
    private boolean fallenBehind = false;

    @Override
    public void reportFallenBehind(@NonNull final NodeId id) {
        // for testing, we conclude we have fallen behind even if just 1 node says so
        fallenBehind = true;
    }

    @Override
    public void clearFallenBehind(@NonNull final NodeId id) {
        fallenBehind = false;
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
    public int numReportedFallenBehind() {
        return 0;
    }

    @Override
    public boolean shouldReconnectFrom(@NonNull final NodeId peerId) {
        return false;
    }

    @Override
    public void addRemovePeers(@NonNull final Set<NodeId> added, @NonNull final Set<NodeId> removed) {}
}
