// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateInvalidException;
import com.swirlds.platform.state.signed.SignedStateValidationData;
import com.swirlds.platform.state.signed.SignedStateValidator;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Validates a signed state by summing the amount of weight held by the valid signatures on the state.
 */
public class DefaultSignedStateValidator implements SignedStateValidator {
    private static final Logger logger = LogManager.getLogger(DefaultSignedStateValidator.class);

    private final int hashDepth;
    private final PlatformStateFacade platformStateFacade;

    public DefaultSignedStateValidator(
            @NonNull final PlatformContext platformContext, @NonNull final PlatformStateFacade platformStateFacade) {
        hashDepth = platformContext
                .getConfiguration()
                .getConfigData(StateConfig.class)
                .debugHashDepth();
        this.platformStateFacade = platformStateFacade;
    }

    /**
     * {@inheritDoc}
     */
    public void validate(
            final SignedState signedState, final Roster roster, SignedStateValidationData previousStateData) {
        throwIfOld(signedState, previousStateData);
        signedState.pruneInvalidSignatures(roster);
        signedState.throwIfNotVerifiable();
    }

    /**
     * Check the signed state against metadata from a prior state, and throw if the new state is older. After a
     * reconnect, there is a slight possibility the teacher sent older state than we already had.  In that case we do
     * not want to keep the state, but try again with a different teacher.  This method checks that the received state
     * is at least as new as the state we started with, and throws if the received state is not acceptable.
     *
     * @param signedState       a newly received signed state from a reconnect process.
     * @param previousStateData The validation data from the current address book and prior state, before reconnect.
     * @throws SignedStateInvalidException if the signed state is not at least as new as the previous state.
     */
    private void throwIfOld(final SignedState signedState, final SignedStateValidationData previousStateData)
            throws SignedStateInvalidException {

        State state = signedState.getState();
        if (platformStateFacade.roundOf(state) < previousStateData.round()
                || platformStateFacade.consensusTimestampOf(state).isBefore(previousStateData.consensusTimestamp())) {
            logger.error(
                    EXCEPTION.getMarker(),
                    """
                            State is too old. Failed reconnect state:
                            {}
                            Original reconnect state:
                            {}""",
                    platformStateFacade.getInfoString(state, hashDepth),
                    previousStateData.getInfoString());
            throw new SignedStateInvalidException(("Received signed state is for a round smaller than or a "
                            + "consensus earlier than what we started with. Original round %d, received round %d. "
                            + "Original timestamp %s, received timestamp %s.")
                    .formatted(
                            previousStateData.round(),
                            signedState.getRound(),
                            previousStateData.consensusTimestamp(),
                            signedState.getConsensusTimestamp()));
        }
    }
}
