// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.triggers.SelfEventReachedConsensusTrigger;
import org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger;
import org.hiero.consensus.status.triggers.StateWrittenToDiskTrigger;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#STARTING_UP} status.
 */
public class StartingUpStatusLogic extends AbstractStatusLogic {
    /**
     * The platform status config
     */
    private final PlatformStatusConfig config;

    /**
     * Constructor
     *
     * @param config the platform status config
     */
    public StartingUpStatusLogic(@NonNull final PlatformStatusConfig config) {
        super(PlatformStatus.STARTING_UP);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * Receiving a {@link SelfEventReachedConsensusTrigger} while in {@link PlatformStatus#STARTING_UP} throws an
     * exception, since this is not conceivable in standard operation.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onSelfEventReachedConsensus(@NonNull final SelfEventReachedConsensusTrigger trigger) {
        return illegal(trigger);
    }

    /**
     * {@link PlatformStatus#STARTING_UP} status unconditionally transitions to {@link PlatformStatus#REPLAYING_EVENTS}
     * when a {@link StartedReplayingEventsTrigger} is processed.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onStartedReplayingEvents(@NonNull final StartedReplayingEventsTrigger trigger) {
        return new ReplayingEventsStatusLogic(config);
    }

    /**
     * Receiving a {@link StateWrittenToDiskTrigger} while in {@link PlatformStatus#STARTING_UP} throws an exception,
     * since this is not conceivable in standard operation.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onStateWrittenToDisk(@NonNull final StateWrittenToDiskTrigger trigger) {
        return illegal(trigger);
    }
}
