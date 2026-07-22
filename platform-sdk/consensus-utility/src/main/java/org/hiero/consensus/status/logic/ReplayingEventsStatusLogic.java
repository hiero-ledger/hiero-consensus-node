// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.actions.DoneReplayingEventsAction;
import org.hiero.consensus.status.actions.FreezePeriodEnteredAction;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#REPLAYING_EVENTS} status.
 */
public class ReplayingEventsStatusLogic extends AbstractStatusLogic {
    /**
     * The platform status config
     */
    private final PlatformStatusConfig config;

    /**
     * The round number of the freeze period if one has been entered, otherwise null
     */
    private Long freezeRound = null;

    /**
     * Constructor
     *
     * @param config the platform status config
     */
    public ReplayingEventsStatusLogic(@NonNull final PlatformStatusConfig config) {
        super(PlatformStatus.REPLAYING_EVENTS);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * If a freeze boundary wasn't crossed while replaying events, then receiving a {@link DoneReplayingEventsAction}
     * causes a transition to {@link PlatformStatus#OBSERVING}.
     * <p>
     * If a freeze boundary was crossed while replaying events, then the {@link DoneReplayingEventsAction} doesn't affect
     * the state machine. The status will remain in {@link PlatformStatus#REPLAYING_EVENTS} until the freeze state has
     * been saved.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onDoneReplayingEvents(@NonNull final DoneReplayingEventsAction action) {
        if (freezeRound != null) {
            // if a freeze boundary was crossed, we won't transition out of this state until the freeze state
            // has been saved
            return this;
        } else {
            return new ObservingStatusLogic(action.instant(), config);
        }
    }

    /**
     * Receiving a {@link FreezePeriodEnteredAction} while in {@link PlatformStatus#REPLAYING_EVENTS} doesn't ever result
     * in a status transition, but this logic method does record the freeze round, which will inform the status
     * progression later.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onFreezePeriodEntered(@NonNull final FreezePeriodEnteredAction action) {
        validateFreezeRound(freezeRound, action);
        freezeRound = action.freezeRound();
        return this;
    }
}
