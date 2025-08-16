// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import static org.hiero.consensus.event.creator.impl.EventCreationStatus.OVERLOADED;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import org.hiero.consensus.event.creator.impl.EventCreationStatus;

public class SyncLagRule implements EventCreationRule {

    private final int maxAllowedSyncLag;
    private final Supplier<Double> getSyncRoundLag;

    public SyncLagRule(final int maxAllowedSyncLag, final Supplier<Double> getSyncRoundLag) {
        this.maxAllowedSyncLag = maxAllowedSyncLag;
        this.getSyncRoundLag = getSyncRoundLag;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEventCreationPermitted() {
        return getSyncRoundLag.get() < maxAllowedSyncLag;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void eventWasCreated() {
        // do nothing
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public EventCreationStatus getEventCreationStatus() {
        return OVERLOADED;
    }
}
