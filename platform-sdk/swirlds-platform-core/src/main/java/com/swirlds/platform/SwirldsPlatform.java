// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.roster.RosterMetrics.registerRosterMetrics;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.metrics.RuntimeMetrics;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.wiring.PlatformCoordinator;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
import org.hiero.consensus.ConsensusLayerInputs;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * The swirlds consensus node platform. Responsible for the creation, gossip, and consensus of events. Also manages the
 * transaction handling and state management.
 */
public class SwirldsPlatform implements Platform {

    private static final Logger logger = LogManager.getLogger();

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
            @NonNull final ConsensusLayerBuildingBlocks buildingBlocks,
            final long initialAncientThreshold,
            final long startingRound) {
        this.inputs = requireNonNull(inputs);
        this.buildingBlocks = requireNonNull(buildingBlocks);
        this.platformContext = PlatformContext.create(
                inputs.configuration(),
                inputs.time(),
                inputs.metrics(),
                inputs.fileSystemManager(),
                inputs.recycleBin());
        this.platformCoordinator = platformCoordinator;
        this.initialAncientThreshold = initialAncientThreshold;
        this.startingRound = startingRound;

        selfId = inputs.selfId();

        notificationEngine = buildingBlocks.notificationEngine();
        currentRoster = inputs.rosterHistory().getCurrentRoster();

        final Metrics metrics = inputs.metrics();
        registerRosterMetrics(metrics, currentRoster, selfId);

        RuntimeMetrics.setup(metrics);

        keysAndCerts = inputs.keysAndCerts();
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

        buildingBlocks.pcesModule().replayPcesEvents(initialAncientThreshold, startingRound);
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
