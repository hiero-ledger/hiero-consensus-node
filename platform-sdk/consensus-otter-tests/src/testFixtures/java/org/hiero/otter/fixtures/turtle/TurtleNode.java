// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.state.signed.StartupStateUtils.getInitialState;
import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.APP_NAME;
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.SWIRLD_NAME;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.model.DeterministicWiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.address.AddressBookUtils;
import com.swirlds.platform.test.fixtures.turtle.gossip.SimulatedGossip;
import com.swirlds.platform.test.fixtures.turtle.gossip.SimulatedNetwork;
import com.swirlds.platform.test.fixtures.turtle.runner.TurtleTestingToolState;
import com.swirlds.platform.util.RandomBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;

/**
 * A node in the turtle network.
 *
 * <p>This class implements the {@link Node} interface and provides methods to control the state of the node.
 */
public class TurtleNode implements Node, TurtleTimeManager.TimeTickReceiver {

    public static final String THREAD_CONTEXT_NODE_ID = "nodeId";
    private static final Logger log = LogManager.getLogger(TurtleNode.class);

    private enum LifeCycle {
        INIT,
        STARTED,
        SHUTDOWN,
        DESTROYED
    }

    private final NodeId selfId;

    private final Randotron randotron;
    private final Time time;
    private final AddressBook addressBook;
    private final KeysAndCerts privateKeys;
    private final SimulatedNetwork network;
    private final TurtleNodeConfiguration nodeConfiguration;

    private DeterministicWiringModel model;
    private Platform platform;
    private LifeCycle lifeCycle = LifeCycle.INIT;

    public TurtleNode(
            @NonNull final Randotron randotron,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final AddressBook addressBook,
            @NonNull final KeysAndCerts privateKeys,
            @NonNull final SimulatedNetwork network,
            @NonNull final Path outputDirectory) {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            this.randotron = requireNonNull(randotron);
            this.time = requireNonNull(time);
            this.selfId = requireNonNull(selfId);
            this.addressBook = requireNonNull(addressBook);
            this.privateKeys = requireNonNull(privateKeys);
            this.network = requireNonNull(network);
            this.nodeConfiguration = new TurtleNodeConfiguration(outputDirectory);

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void failUnexpectedly(@NonNull final Duration timeout) throws InterruptedException {
        doShutdownNode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdownGracefully(@NonNull final Duration timeout) throws InterruptedException {
        log.warn("Simulating a graceful shutdown of a node has not been implemented yet.");
        doShutdownNode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revive(@NonNull final Duration timeout) {
        checkLifeCycle(LifeCycle.STARTED, "Node has already been started.");
        checkLifeCycle(LifeCycle.DESTROYED, "Node has already been destroyed.");

        // Start node from current state
        doStartNode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransaction(@NonNull final byte[] transaction) {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            checkLifeCycle(LifeCycle.INIT, "Node has not been started yet.");
            checkLifeCycle(LifeCycle.SHUTDOWN, "Node has been shut down.");
            checkLifeCycle(LifeCycle.DESTROYED, "Node has been destroyed.");

            platform.createTransaction(transaction);

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration getConfiguration() {
        return nodeConfiguration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull Instant now) {
        if (lifeCycle == LifeCycle.STARTED) {
            try {
                ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());
                model.tick();
            } finally {
                ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
            }
        }
    }

    /**
     * Start the node
     */
    public void start() {
        try {
            ThreadContext.put(THREAD_CONTEXT_NODE_ID, selfId.toString());

            checkLifeCycle(LifeCycle.STARTED, "Node has already been started.");
            checkLifeCycle(LifeCycle.DESTROYED, "Node has already been destroyed.");

            // Clean the output directory and start the node
            // TODO: Wipe the output directory if it exists
            doStartNode();

        } finally {
            ThreadContext.remove(THREAD_CONTEXT_NODE_ID);
        }
    }

    /**
     * Shuts down the node and cleans up resources. Once this method is called, the node cannot be started again. This
     * method is idempotent and can be called multiple times without any side effects.
     */
    public void destroy() throws InterruptedException {
        doShutdownNode();
        lifeCycle = LifeCycle.DESTROYED;
    }

    private void checkLifeCycle(@NonNull final LifeCycle expected, @NonNull final String message) {
        if (lifeCycle == expected) {
            throw new IllegalStateException(message);
        }
    }

    private void doShutdownNode() throws InterruptedException {
        if (lifeCycle == LifeCycle.STARTED) {
            // TODO: Release all resources
            getMetricsProvider().removePlatformMetrics(platform.getSelfId());
            platform = null;
            model = null;
        }
        lifeCycle = LifeCycle.SHUTDOWN;
    }

    private void doStartNode() {

        final Configuration currentConfiguration = nodeConfiguration.createConfiguration();

        setupGlobalMetrics(currentConfiguration);

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withTime(time)
                .withConfiguration(currentConfiguration)
                .build();

        model = WiringModelBuilder.create(platformContext.getMetrics(), time)
                .withDeterministicModeEnabled(true)
                .build();
        final SemanticVersion version =
                SemanticVersion.newBuilder().major(1).build();
        final PlatformStateFacade platformStateFacade = new PlatformStateFacade();
        MerkleDb.resetDefaultInstancePath();
        final Metrics metrics = getMetricsProvider().createPlatformMetrics(selfId);
        final FileSystemManager fileSystemManager = FileSystemManager.create(currentConfiguration);
        final RecycleBin recycleBin =
                RecycleBin.create(metrics, currentConfiguration, getStaticThreadManager(), time, fileSystemManager,
                        selfId);

        final HashedReservedSignedState reservedState = getInitialState(
                recycleBin,
                version,
                TurtleTestingToolState::getStateRootNode,
                APP_NAME,
                SWIRLD_NAME,
                selfId,
                addressBook,
                platformStateFacade,
                platformContext);
        final ReservedSignedState initialState = reservedState.state();

        final PlatformBuilder platformBuilder = PlatformBuilder.create(
                        APP_NAME,
                        SWIRLD_NAME,
                        version,
                        initialState,
                        TurtleApp.INSTANCE,
                        selfId,
                        AddressBookUtils.formatConsensusEventStreamName(addressBook, selfId),
                        RosterUtils.buildRosterHistory(initialState.get().getState(), platformStateFacade),
                        platformStateFacade)
                .withModel(model)
                .withRandomBuilder(new RandomBuilder(randotron.nextLong()))
                .withKeysAndCerts(privateKeys)
                .withPlatformContext(platformContext)
                .withConfiguration(currentConfiguration)
                .withSystemTransactionEncoderCallback(TurtleApp::encodeSystemTransaction);

        final PlatformComponentBuilder platformComponentBuilder = platformBuilder.buildComponentBuilder();

        final SimulatedGossip gossip = network.getGossipInstance(selfId);
        gossip.provideIntakeEventCounter(
                platformComponentBuilder.getBuildingBlocks().intakeEventCounter());

        platformComponentBuilder.withMetricsDocumentationEnabled(false).withGossip(network.getGossipInstance(selfId));

        platform = platformComponentBuilder.build();
        platform.start();

        lifeCycle = LifeCycle.STARTED;
    }
}
