// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import static org.hiero.consensus.event.creator.impl.EventCreationStatus.PLATFORM_STATUS;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.event.creator.impl.EventCreationStatus;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;

/**
 * Limits the creation of new events depending on the current platform status.
 */
public class PlatformStatusRule implements EventCreationRule {

    /**
     * The current platform status.
     */
    private PlatformStatus platformStatus;

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEventCreationPermitted() {
        final PlatformStatus currentStatus = this.platformStatus;

        return currentStatus == PlatformStatus.ACTIVE || currentStatus == PlatformStatus.CHECKING || currentStatus == PlatformStatus.FREEZING;
    }

    public void setPlatformStatus(final PlatformStatus platformStatus) {
        this.platformStatus = platformStatus;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void eventWasCreated() {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public EventCreationStatus getEventCreationStatus() {
        return PLATFORM_STATUS;
    }
}
