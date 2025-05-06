// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.runner;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.state.signed.StartupStateUtils.getInitialState;
import static com.swirlds.platform.test.fixtures.turtle.runner.TurtleConsensusStateEventHandler.TURTLE_CONSENSUS_STATE_EVENT_HANDLER;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.base.time.Time;
import com.swirlds.common.config.StateCommonConfig_;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.config.FileSystemManagerConfig_;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.DeterministicWiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.builder.PlatformBuildingBlocks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.config.BasicConfig_;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.address.AddressBookUtils;
import com.swirlds.platform.test.fixtures.turtle.consensus.ConsensusRoundsHolder;
import com.swirlds.platform.test.fixtures.turtle.consensus.ConsensusRoundsListContainer;
import com.swirlds.platform.test.fixtures.turtle.gossip.SimulatedGossip;
import com.swirlds.platform.test.fixtures.turtle.gossip.SimulatedNetwork;
import com.swirlds.platform.util.RandomBuilder;
import com.swirlds.platform.wiring.PlatformSchedulersConfig_;
import com.swirlds.platform.wiring.PlatformWiring;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Encapsulates a single node running in a TURTLE network.
 * <pre>
 *    _________________
 *  /   Testing        \
 * |    Utility         |
 * |    Running         |    _ -
 * |    Totally in a    |=<( o 0 )
 * |    Local           |   \===/
 *  \   Environment    /
 *   ------------------
 *   / /       | | \ \
 *  """        """ """
 * </pre>
 */
public class TurtleNode {

    private final DeterministicWiringModel model;
    private final Platform platform;
    private final ConsensusRoundsHolder consensusRoundsHolder;

    @NonNull
    private static Configuration createBasicConfiguration(final @NonNull Path outputDirectory) {
        return new TestConfigBuilder()
                .withValue(PlatformSchedulersConfig_.CONSENSUS_EVENT_STREAM, "NO_OP")
                .withValue(BasicConfig_.JVM_PAUSE_DETECTOR_SLEEP_MS, "0")
                .withValue(StateCommonConfig_.SAVED_STATE_DIRECTORY, outputDirectory.toString())
                .withValue(FileSystemManagerConfig_.ROOT_PATH, outputDirectory.toString())
                .getOrCreateConfig();
    }

    /**
     * Create a new TurtleNode. Simulates a single consensus node in a TURTLE network.
     *
     * @param randotron   a source of randomness
     * @param time        the current time
     * @param nodeId      the ID of this node
     * @param addressBook the address book for the network
     * @param privateKeys the private keys for this node
     * @param network     the simulated network
     * @param outputDirectory the directory where the node output will be stored, like saved state and so on
     */
    public TurtleNode(
            @NonNull final Randotron randotron,
            @NonNull final Time time,
            @NonNull final NodeId nodeId,
            @NonNull final AddressBook addressBook,
            @NonNull final KeysAndCerts privateKeys,
            @NonNull final SimulatedNetwork network,
            @NonNull final Path outputDirectory) {
        this(randotron, time, nodeId, addressBook, privateKeys, network, createBasicConfiguration(outputDirectory));
    }

    /**
     * Create a new TurtleNode. Simulates a single consensus node in a TURTLE network.
     *
     * @param randotron   a source of randomness
     * @param time        the current time
     * @param nodeId      the ID of this node
     * @param addressBook the address book for the network
     * @param privateKeys the private keys for this node
     * @param network     the simulated network
     * @param configuration the configuration for this node
     */
    public TurtleNode(
            @NonNull final Randotron randotron,
            @NonNull final Time time,
            @NonNull final NodeId nodeId,
            @NonNull final AddressBook addressBook,
            @NonNull final KeysAndCerts privateKeys,
            @NonNull final SimulatedNetwork network,
            @NonNull final Configuration configuration) {

        setupGlobalMetrics(configuration);

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withTime(time)
                .withConfiguration(configuration)
                .build();

        model = WiringModelBuilder.create(platformContext.getMetrics(), time)
                .withDeterministicModeEnabled(true)
                .build();
        final SemanticVersion softwareVersion =
                SemanticVersion.newBuilder().major(1).build();
        final PlatformStateFacade platformStateFacade = new PlatformStateFacade();
        final var version = SemanticVersion.newBuilder().major(1).build();
        MerkleDb.resetDefaultInstancePath();
        final var metrics = getMetricsProvider().createPlatformMetrics(nodeId);
        final var fileSystemManager = FileSystemManager.create(configuration);
        final var recycleBin =
                RecycleBin.create(metrics, configuration, getStaticThreadManager(), time, fileSystemManager, nodeId);

        final var reservedState = getInitialState(
                recycleBin,
                version,
                TurtleTestingToolState::getStateRootNode,
                "foo",
                "bar",
                nodeId,
                addressBook,
                platformStateFacade,
                platformContext);
        final var initialState = reservedState.state();

        final State state = initialState.get().getState();
        final long round = platformStateFacade.roundOf(state);
        final PlatformBuilder platformBuilder = PlatformBuilder.create(
                        "foo",
                        "bar",
                        softwareVersion,
                        initialState,
                        TURTLE_CONSENSUS_STATE_EVENT_HANDLER,
                        nodeId,
                        AddressBookUtils.formatConsensusEventStreamName(addressBook, nodeId),
                        RosterUtils.buildRosterHistory(initialState.get().getState(), round),
                        platformStateFacade)
                .withModel(model)
                .withRandomBuilder(new RandomBuilder(randotron.nextLong()))
                .withKeysAndCerts(privateKeys)
                .withPlatformContext(platformContext)
                .withConfiguration(configuration)
                .withSystemTransactionEncoderCallback(StateSignatureTransaction.PROTOBUF::toBytes);

        final PlatformComponentBuilder platformComponentBuilder = platformBuilder.buildComponentBuilder();

        final PlatformBuildingBlocks buildingBlocks = platformComponentBuilder.getBuildingBlocks();

        final ComponentWiring<ConsensusRoundsHolder, Void> consensusRoundsHolderWiring =
                new ComponentWiring<>(model, ConsensusRoundsHolder.class, TaskSchedulerConfiguration.parse("DIRECT"));

        consensusRoundsHolder = new ConsensusRoundsListContainer(nodeId);
        consensusRoundsHolderWiring.bind(consensusRoundsHolder);

        final InputWire<List<ConsensusRound>> consensusRoundsHolderInputWire =
                consensusRoundsHolderWiring.getInputWire(ConsensusRoundsHolder::interceptRounds);

        final PlatformWiring platformWiring = buildingBlocks.platformWiring();
        final OutputWire<List<ConsensusRound>> consensusEngineOutputWire =
                platformWiring.getConsensusEngineOutputWire();
        consensusEngineOutputWire.solderTo(consensusRoundsHolderInputWire);

        final SimulatedGossip gossip = network.getGossipInstance(nodeId);
        gossip.provideIntakeEventCounter(
                platformComponentBuilder.getBuildingBlocks().intakeEventCounter());

        platformComponentBuilder.withMetricsDocumentationEnabled(false).withGossip(network.getGossipInstance(nodeId));

        platform = platformComponentBuilder.build();
    }

    /**
     * Returns the {@link Configuration} of this node.
     *
     * @return the {@link Configuration} of this node
     */
    public Configuration getConfiguration() {
        return platform.getContext().getConfiguration();
    }

    /**
     * Start this node.
     */
    public void start() {
        platform.start();
    }

    /**
     * Simulate the next time step for this node.
     */
    public void tick() {
        model.tick();
    }

    /**
     * Submit a transaction to the node.
     *
     * @param transaction the transaction to submit
     */
    public void submitTransaction(@NonNull final byte[] transaction) {
        platform.createTransaction(transaction);
    }

    @NonNull
    public ConsensusRoundsHolder getConsensusRoundsHolder() {
        return consensusRoundsHolder;
    }

    /**
     * Shut down the node immediately. No attempt is made to finish ongoing tasks or to save
     * the current state. All resources are released. This method is idempotent and can be
     * called multiple times without any side effects after the first call.
     */
    public void destroy() throws InterruptedException {
        getMetricsProvider().removePlatformMetrics(platform.getSelfId());
    }
}
