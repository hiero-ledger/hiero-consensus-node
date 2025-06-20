// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.node.NodeId;

/**
 * A no-op implementation of {@link IntakeEventCounter}.
 */
public class NoOpIntakeEventCounter implements IntakeEventCounter {
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasUnprocessedEvents(@NonNull NodeId peer) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void eventEnteredIntakePipeline(@NonNull NodeId peer) {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void eventExitedIntakePipeline(@Nullable NodeId peer) {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        // no-op
    }
}
