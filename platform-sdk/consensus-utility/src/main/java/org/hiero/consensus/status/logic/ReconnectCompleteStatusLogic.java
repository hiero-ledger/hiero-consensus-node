// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.triggers.FallenBehindTrigger;
import org.hiero.consensus.status.triggers.FreezePeriodEnteredTrigger;
import org.hiero.consensus.status.triggers.StateWrittenToDiskTrigger;

/**
 * Class containing the state machine logic for the {@link PlatformStatus#RECONNECT_COMPLETE} status.
 */
public class ReconnectCompleteStatusLogic extends AbstractStatusLogic {
    /**
     * The platform status config
     */
    private final PlatformStatusConfig config;

    /**
     * The round number of the reconnect state that was received
     */
    private final long reconnectStateRound;

    /**
     * The round number of the freeze period if one has been entered, otherwise null
     */
    private Long freezeRound;

    /**
     * Constructor
     *
     * @param reconnectStateRound the round number of the reconnect state that was received
     * @param freezeRound         the round number of the freeze period if one has been entered, otherwise null
     * @param config              the platform status config
     */
    public ReconnectCompleteStatusLogic(
            final long reconnectStateRound,
            @Nullable final Long freezeRound,
            @NonNull final PlatformStatusConfig config) {

        super(PlatformStatus.RECONNECT_COMPLETE);
        this.reconnectStateRound = reconnectStateRound;
        this.freezeRound = freezeRound;
        this.config = Objects.requireNonNull(config);
    }

    /**
     * {@link PlatformStatus#RECONNECT_COMPLETE} status unconditionally transitions to {@link PlatformStatus#BEHIND} when
     * a {@link FallenBehindTrigger} is processed.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onFallenBehind(@NonNull final FallenBehindTrigger trigger) {
        return new BehindStatusLogic(config);
    }

    /**
     * Receiving a {@link FreezePeriodEnteredTrigger} while in {@link PlatformStatus#RECONNECT_COMPLETE} doesn't ever
     * result in a status transition, but this logic method does record the freeze round, which will inform the status
     * progression once the reconnect state has been saved.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onFreezePeriodEntered(@NonNull final FreezePeriodEnteredTrigger trigger) {
        freezeRound = validateFreezeRound(freezeRound, trigger);
        return this;
    }

    /**
     * Receiving a {@link StateWrittenToDiskTrigger} while in {@link PlatformStatus#RECONNECT_COMPLETE} causes a
     * transition to {@link PlatformStatus#FREEZE_COMPLETE} if it's a freeze state.
     * <p>
     * For non-freeze states, if the state written to disk is prior to the reconnect state round, it's old, so we need to
     * wait until the reconnect state is written to disk (or a later state). If the state written to disk is the
     * reconnect state or later, then we can transition to a new status. If a freeze boundary has been crossed, we
     * transition to {@link PlatformStatus#FREEZING} status. Otherwise, we transition to {@link PlatformStatus#CHECKING}
     * status.
     */
    @NonNull
    @Override
    protected PlatformStatusLogic onStateWrittenToDisk(@NonNull final StateWrittenToDiskTrigger trigger) {
        if (trigger.isFreezeState()) {
            return new FreezeCompleteStatusLogic();
        }

        if (trigger.round() < reconnectStateRound) {
            // if the state written to disk is prior to the reconnect state round, it's old.
            // we need to wait until the reconnected state is written to disk (or a later state)
            return this;
        }

        // always transition to a new status once the reconnect state has been written to disk
        if (freezeRound != null) {
            return new FreezingStatusLogic(freezeRound);
        } else {
            return new CheckingStatusLogic(config);
        }
    }
}
