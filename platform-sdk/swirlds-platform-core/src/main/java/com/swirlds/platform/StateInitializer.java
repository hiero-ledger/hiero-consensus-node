// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static com.swirlds.platform.system.InitTrigger.RESTART;
import static org.hiero.consensus.model.utility.interrupt.Uninterruptable.abortAndThrowIfInterrupted;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Encapsulates the logic for calling
 * {@link ConsensusStateEventHandler#onStateInitialized(MerkleNodeState, Platform, InitTrigger, SemanticVersion)}
 * startup time.
 */
public final class StateInitializer {

    private static final Logger logger = LogManager.getLogger(StateInitializer.class);

    private StateInitializer() {}

    /**
     * Initialize the state.
     *
     * @param platform        the platform instance
     * @param platformContext the platform context
     * @param signedState     the state to initialize
     */
    public static void initializeState(
            @NonNull final Platform platform,
            @NonNull final PlatformContext platformContext,
            @NonNull final SignedState signedState,
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final PlatformStateFacade platformStateFacade) {

        final SemanticVersion previousSoftwareVersion;
        final InitTrigger trigger;

        if (signedState.isGenesisState()) {
            previousSoftwareVersion = null;
            trigger = GENESIS;
        } else {
            previousSoftwareVersion = platformStateFacade.creationSoftwareVersionOf(signedState.getState());
            trigger = RESTART;
        }

        final MerkleNodeState initialState = signedState.getState();

        // Although the state from disk / genesis state is initially hashed, we are actually dealing with a copy
        // of that state here. That copy should have caused the hash to be cleared.

        if (initialState.getHash() != null) {
            throw new IllegalStateException("Expected initial state to be unhashed");
        }

        signedState.init(platformContext);
        consensusStateEventHandler.onStateInitialized(
                signedState.getState(), platform, trigger, previousSoftwareVersion);

        abortAndThrowIfInterrupted(
                () -> {
                    try {
                        platformContext
                                .getMerkleCryptography()
                                .digestTreeAsync(initialState.getRoot())
                                .get();
                    } catch (final ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                },
                "interrupted while attempting to hash the state");

        // If our hash changes as a result of the new address book then our old signatures may become invalid.
        signedState.pruneInvalidSignatures();

        final StateConfig stateConfig = platformContext.getConfiguration().getConfigData(StateConfig.class);
        logger.info(
                STARTUP.getMarker(),
                """
                        The platform is using the following initial state:
                        {}""",
                platformStateFacade.getInfoString(signedState.getState(), stateConfig.debugHashDepth()));
    }
}
