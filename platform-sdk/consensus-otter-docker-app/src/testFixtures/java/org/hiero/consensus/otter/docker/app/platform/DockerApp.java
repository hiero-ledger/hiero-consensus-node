// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.platform;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.initLogging;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.state.signed.StartupStateUtils.loadInitialState;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.builder.PlatformBuildingBlocks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.platform.wiring.PlatformWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.otter.fixtures.turtle.app.TurtleAppState;

/**
 * The main application class for the container-based consensus node networks.
 */
public class DockerApp {
    private static final Logger LOGGER = LogManager.getLogger(DockerApp.class);

    private static final String APP_NAME = "org.hiero.consensus.otter.docker.app.platform.DockerApp";
    private static final String SWIRLD_NAME = "123";

    private final Platform platform;
    private final AtomicReference<PlatformStatus> status = new AtomicReference<>();
    private final List<Consumer<List<ConsensusRound>>> consensusRoundListeners = new CopyOnWriteArrayList<>();

    /**
     * Creates a new DockerApp instance with the specified parameters.
     *
     * @param selfId               the unique identifier for this node
     * @param version              the semantic version of the application
     * @param genesisRoster        the initial roster of nodes in the network
     * @param keysAndCerts         the keys and certificates for this node
     * @param overriddenProperties properties to override in the configuration
     */
    public DockerApp(
            @NonNull final NodeId selfId,
            @NonNull final SemanticVersion version,
            @NonNull final Roster genesisRoster,
            @NonNull final KeysAndCerts keysAndCerts,
            @Nullable final Map<String, String> overriddenProperties) {
        // --- Configure platform infrastructure and derive node id from the command line and environment ---
        initLogging();
        BootstrapUtils.setupConstructableRegistry();

        final var oldSelfId = org.hiero.consensus.model.node.NodeId.of(selfId.id());
        final TestConfigBuilder configurationBuilder = new TestConfigBuilder();
        if (overriddenProperties != null) {
            overriddenProperties.forEach(configurationBuilder::withValue);
        }
        final Configuration platformConfig = configurationBuilder.getOrCreateConfig();

        // Immediately initialize the cryptography and merkle cryptography factories
        // to avoid using default behavior instead of that defined in platformConfig
        final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(platformConfig);

        // --- Initialize the platform metrics and the Hedera instance ---
        setupGlobalMetrics(platformConfig);
        final Metrics metrics = getMetricsProvider().createPlatformMetrics(oldSelfId);
        final PlatformStateFacade platformStateFacade = new PlatformStateFacade();

        LOGGER.info("Starting node {} with version {}", selfId, version);

        // --- Build required infrastructure to load the initial state, then initialize the States API ---
        final Time time = Time.getCurrent();
        final FileSystemManager fileSystemManager = FileSystemManager.create(platformConfig);
        final RecycleBin recycleBin = RecycleBin.create(
                metrics, platformConfig, getStaticThreadManager(), time, fileSystemManager, oldSelfId);

        final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler = new DockerStateEventHandler();

        final PlatformContext platformContext = PlatformContext.create(
                platformConfig, Time.getCurrent(), metrics, fileSystemManager, recycleBin, merkleCryptography);

        final HashedReservedSignedState reservedState = loadInitialState(
                recycleBin,
                version,
                () -> TurtleAppState.createGenesisState(platformConfig, genesisRoster, version),
                APP_NAME,
                SWIRLD_NAME,
                oldSelfId,
                platformStateFacade,
                platformContext);
        final ReservedSignedState initialState = reservedState.state();

        // --- Create the platform context and initialize the cryptography ---
        final MerkleNodeState state = initialState.get().getState();
        final RosterHistory rosterHistory = RosterUtils.createRosterHistory(state);

        // --- Build the platform component builder so we can access wiring ---
        final PlatformBuilder builder = PlatformBuilder.create(
                        APP_NAME,
                        SWIRLD_NAME,
                        version,
                        initialState,
                        consensusStateEventHandler,
                        oldSelfId,
                        selfId.toString(),
                        rosterHistory,
                        platformStateFacade)
                .withPlatformContext(platformContext)
                .withConfiguration(platformConfig)
                .withKeysAndCerts(keysAndCerts)
                .withSystemTransactionEncoderCallback(DockerApp::encodeSystemTransaction);

        final PlatformComponentBuilder componentBuilder = builder.buildComponentBuilder();
        final PlatformBuildingBlocks blocks = componentBuilder.getBuildingBlocks();
        final PlatformWiring wiring = blocks.platformWiring();

        // Forward consensus rounds to registered listeners
        wiring.getConsensusEngineOutputWire()
                .solderTo("dockerApp", "consensusRounds", this::notifyConsensusRoundListeners);

        // Build the platform
        platform = componentBuilder.build();

        platform.getNotificationEngine()
                .register(PlatformStatusChangeListener.class, newStatus -> status.set(newStatus.getNewStatus()));
    }

    /**
     * Starts the application.
     */
    public void start() {
        platform.start();
    }

    /**
     * Gets the current value for the {@link PlatformStatus} holder
     * @return current {@link PlatformStatus} maybe {@code null}
     */
    public PlatformStatus getStatus() {
        return status.get();
    }

    /**
     * Destroys the application. Once this method is called, the application cannot be used again.
     *
     * @throws InterruptedException if the thread is interrupted while waiting for the platform to shut down
     */
    public void destroy() throws InterruptedException {
        platform.destroy();
    }

    /**
     * Encodes a {@link StateSignatureTransaction}
     *
     * @param stateSignatureTransaction the transaction to encode
     * @return the encoded transaction as a {@link Bytes} object
     */
    private static Bytes encodeSystemTransaction(@NonNull final StateSignatureTransaction stateSignatureTransaction) {
        return Bytes.EMPTY; // FIXME
    }

    public void registerPlatformStatusChangeListener(@NonNull final PlatformStatusChangeListener listener) {
        platform.getNotificationEngine().register(PlatformStatusChangeListener.class, listener);
    }

    private void notifyConsensusRoundListeners(@NonNull final List<ConsensusRound> rounds) {
        consensusRoundListeners.forEach(listener -> listener.accept(rounds));
    }

    public void registerConsensusRoundListener(@NonNull final Consumer<List<ConsensusRound>> listener) {
        consensusRoundListeners.add(listener);
    }
}
