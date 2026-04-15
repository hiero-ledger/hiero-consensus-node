// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components;

import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static org.hiero.consensus.state.snapshot.StateToDiskReason.RECONNECT;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.eventhandling.StateWithHashComplexity;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.snapshot.StateToDiskReason;

/**
 * The default implementation of {@link SavedStateController}.
 */
public class DefaultSavedStateController implements SavedStateController {
    private static final Logger logger = LogManager.getLogger(DefaultSavedStateController.class);

    /**
     * Constructor
     *
     * @param platformContext the platform context
     */
    public DefaultSavedStateController(@NonNull final PlatformContext platformContext) {}

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public StateWithHashComplexity markSavedState(@NonNull final StateWithHashComplexity stateWithHashComplexity) {
        final ReservedSignedState reservedSignedState = stateWithHashComplexity.reservedSignedState();
        final SignedState signedState = reservedSignedState.get();
        final StateToDiskReason reason = signedState.getRequestedStateToSaveReason();
        if (reason != null) {
            markSavingToDisk(reservedSignedState, reason);
        }
        return stateWithHashComplexity;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reconnectStateReceived(@NonNull final ReservedSignedState reservedSignedState) {
        try (reservedSignedState) {
            markSavingToDisk(reservedSignedState, RECONNECT);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerSignedStateFromDisk(@NonNull final SignedState signedState) {}

    /**
     * Marks a signed state with a reason why it should eventually be written to disk
     *
     * @param state  the state to mark
     * @param reason the reason why the state should be written to disk
     */
    private void markSavingToDisk(@NonNull final ReservedSignedState state, @NonNull final StateToDiskReason reason) {
        final SignedState signedState = state.get();
        logger.info(
                STATE_TO_DISK.getMarker(),
                "Signed state from round {} created, will eventually be written to disk, for reason: {}",
                signedState.getRound(),
                reason);

        signedState.markAsStateToSave(reason);
    }
}
