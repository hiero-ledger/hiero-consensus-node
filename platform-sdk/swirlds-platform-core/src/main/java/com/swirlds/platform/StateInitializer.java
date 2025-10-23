// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static com.swirlds.platform.system.InitTrigger.RESTART;
import static org.hiero.base.concurrent.interrupt.Uninterruptable.abortAndThrowIfInterrupted;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
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

        if (initialState.isHashed()) {
            throw new IllegalStateException("Expected initial state to be unhashed");
        }

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

    /**
     * Initializes a {@link MerkleNodeState} from the given state root.
     * <p>
     * If the state root is an instance of {@link VirtualMap}, it means this is a "Mega Map" and provided function
     * is used to create the {@code MerkleNodeState} (i.e. {@code HederaVirtualMapState}). Otherwise, it casts the state root directly
     * to {@code MerkleNodeState}.
     * </p>
     *
     * @deprecated This method should be removed together with {@code MerkleStateRoot}. Only state root function should be used then.
     * @param createStateFromVirtualMap a function to instantiate the state object from a Virtual Map
     * @param stateRoot                 the root of the state to initialize
     * @param metrics                   the metrics
     * @return the initialized {@code MerkleNodeState}
     */
    @Deprecated
    public static MerkleNodeState initializeMerkleNodeState(
            @NonNull final Function<VirtualMap, MerkleNodeState> createStateFromVirtualMap,
            @NonNull final MerkleNode stateRoot,
            @NonNull final Metrics metrics) {
        if (stateRoot instanceof VirtualMap virtualMap) {
            virtualMap.registerMetrics(metrics);
            return createStateFromVirtualMap.apply(virtualMap);
        } else {
            return (MerkleNodeState) stateRoot;
        }
    }
}
