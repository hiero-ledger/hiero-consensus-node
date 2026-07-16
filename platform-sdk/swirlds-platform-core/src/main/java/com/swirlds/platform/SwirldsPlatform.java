// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static com.swirlds.platform.system.InitTrigger.RESTART;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.concurrent.interrupt.Uninterruptable.abortAndThrowIfInterrupted;
import static org.hiero.consensus.platformstate.PlatformStateUtils.ancientThresholdOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.consensusSnapshotOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.creationSoftwareVersionOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.getInfoString;
import static org.hiero.consensus.platformstate.PlatformStateUtils.legacyRunningEventHashOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.setCreationSoftwareVersionTo;
import static org.hiero.consensus.roster.RosterMetrics.registerRosterMetrics;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.metrics.RuntimeMetrics;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.wiring.PlatformCoordinator;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
import org.hiero.consensus.ConsensusLayerInputs;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.stream.RunningEventHashOverride;
import org.hiero.consensus.round.EventWindowUtils;
import org.hiero.consensus.state.SavedStateController;
import org.hiero.consensus.state.config.StateConfig;
import org.hiero.consensus.state.persistence.SignedStateFilePath;
import org.hiero.consensus.state.saved.SavedStateInfo;
import org.hiero.consensus.state.signed.SignedState;

/**
 * The swirlds consensus node platform. Responsible for the creation, gossip, and consensus of events. Also manages the
 * transaction handling and state management.
 */
public class SwirldsPlatform implements Platform {

    private static final Logger logger = LogManager.getLogger(SwirldsPlatform.class);

    /**
     * The unique ID of this node.
     */
    private final NodeId selfId;

    /**
     * the current nodes in the network and their information
     */
    private final Roster currentRoster;

    /**
     * the object that contains all key pairs and CSPRNG state for this member
     */
    private final KeysAndCerts keysAndCerts;

    /**
     * If a state was loaded from disk, this is the minimum generation non-ancient for that round. If starting from a
     * genesis state, this is 0.
     */
    private final long initialAncientThreshold;

    /**
     * The latest round to have reached consensus in the initial state
     */
    private final long startingRound;

    /**
     * For passing notifications between the platform and the application.
     */
    private final NotificationEngine notificationEngine;

    /**
     * Controls which states are saved to disk
     */
    private final SavedStateController savedStateController;

    private final long pcesReplayLowerBound;
    private final PlatformCoordinator platformCoordinator;

    private final PlatformContext platformContext;
    private final ConsensusLayerInputs inputs;
    private final ConsensusLayerBuildingBlocks buildingBlocks;

    /**
     * Constructor.
     */
    public SwirldsPlatform(
            @NonNull final ConsensusLayerInputs inputs,
            @NonNull final PlatformCoordinator platformCoordinator,
            @NonNull final ConsensusLayerBuildingBlocks buildingBlocks) {
        this.inputs = requireNonNull(inputs);
        this.buildingBlocks = requireNonNull(buildingBlocks);
        this.platformContext = PlatformContext.create(
                inputs.configuration(),
                inputs.time(),
                inputs.metrics(),
                inputs.fileSystemManager(),
                inputs.recycleBin());
        this.platformCoordinator = platformCoordinator;

        // The reservation on this state is held by the caller of this constructor.
        final SignedState initialState = inputs.initialState().get();

        selfId = inputs.selfId();

        notificationEngine = buildingBlocks.notificationEngine();

        logger.info(STARTUP.getMarker(), "Starting with roster history:\n{}", inputs.rosterHistory());
        currentRoster = inputs.rosterHistory().getCurrentRoster();

        final Metrics metrics = inputs.metrics();
        registerRosterMetrics(metrics, currentRoster, selfId);

        RuntimeMetrics.setup(metrics);

        keysAndCerts = inputs.keysAndCerts();
        savedStateController = buildingBlocks.savedStateController();

        //////////////////

        initializeState(initialState, inputs.consensusStateEventHandler());


        final boolean startedFromGenesis = initialState.isGenesisState();

        if (startedFromGenesis) {
            initialAncientThreshold = 0;
            startingRound = 0;
        } else {
            initialAncientThreshold = ancientThresholdOf(initialState.getState());
            startingRound = initialState.getRound();
        }

        if (!initialState.isGenesisState()) {
            pcesReplayLowerBound = initialAncientThreshold;
        } else {
            pcesReplayLowerBound = 0;
        }
    }

    /**
     * Initialize the state.
     *
     * @param signedState the state to initialize
     */
    private void initializeState(
            @NonNull final SignedState signedState,
            @NonNull final ConsensusStateEventHandler consensusStateEventHandler) {

        final SemanticVersion previousSoftwareVersion;
        final InitTrigger trigger;

        if (signedState.isGenesisState()) {
            previousSoftwareVersion = null;
            trigger = GENESIS;
        } else {
            previousSoftwareVersion = creationSoftwareVersionOf(signedState.getState());
            trigger = RESTART;
        }

        final State initialState = signedState.getState();

        // Although the state from disk / genesis state is initially hashed, we are actually dealing with a copy
        // of that state here. That copy should have caused the hash to be cleared. The hash must be calculated
        // after onStateInitialized(), so that it includes any changes to the state made in onStateInitialized().

        if (initialState.isHashed()) {
            throw new IllegalStateException("Expected initial state to be unhashed");
        }

        consensusStateEventHandler.onStateInitialized(signedState.getState(), this, trigger, previousSoftwareVersion);

        // calculate hash
        abortAndThrowIfInterrupted(
                initialState::getHash, // calculate hash
                "interrupted while attempting to hash the state");

        // If our hash changes as a result of the new address book then our old signatures may become invalid.
        if (trigger != GENESIS) {
            signedState.pruneInvalidSignatures();
        }

        logger.info(STARTUP.getMarker(), """
                The platform is using the following initial state:
                {}""", getInfoString(signedState.getState()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeId getSelfId() {
        return selfId;
    }

    /**
     * Start this platform.
     */
    @Override
    public void start() {
        logger.info(STARTUP.getMarker(), "Starting platform {}", selfId);

        inputs.recycleBin().start();
        inputs.metrics().start();
        buildingBlocks.wiringModel().start();

        buildingBlocks.pcesModule().replayPcesEvents(pcesReplayLowerBound, startingRound);
        buildingBlocks.gossipModule().start();
    }

    @Override
    public void destroy() throws InterruptedException {
        notificationEngine.shutdown();
        inputs.recycleBin().stop();
        buildingBlocks.wiringModel().stop();
        getMetricsProvider().removePlatformMetrics(selfId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public PlatformContext getContext() {
        return platformContext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NotificationEngine getNotificationEngine() {
        return notificationEngine;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Signature sign(@NonNull final byte[] data) {
        return new PlatformSigner(keysAndCerts).sign(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void quiescenceCommand(@NonNull final QuiescenceCommand quiescenceCommand) {
        platformCoordinator.quiescenceCommand(quiescenceCommand);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Roster getRoster() {
        return currentRoster;
    }
}
