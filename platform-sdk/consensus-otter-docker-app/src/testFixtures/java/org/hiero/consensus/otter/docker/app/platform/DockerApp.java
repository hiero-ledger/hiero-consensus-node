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
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.util.BootstrapUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
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

    /**
     * Creates a new DockerApp instance with the specified parameters.
     *
     * @param selfId              the unique identifier for this node
     * @param version             the semantic version of the application
     * @param genesisRoster       the initial roster of nodes in the network
     * @param keysAndCerts        the keys and certificates for this node
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
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().autoDiscoverExtensions();
        if (overriddenProperties != null) {
            configurationBuilder.withSource(new SimpleConfigSource(overriddenProperties));
        }
        final Configuration platformConfig = configurationBuilder.build();

        // Immediately initialize the cryptography and merkle cryptography factories
        // to avoid using default behavior instead of that defined in platformConfig
        final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(platformConfig);

        // --- Initialize the platform metrics and the Hedera instance ---
        setupGlobalMetrics(platformConfig);
        final Metrics metrics = getMetricsProvider().createPlatformMetrics(selfId);
        final PlatformStateFacade platformStateFacade = new PlatformStateFacade();

        LOGGER.info("Starting node {} with version {}", selfId, version);

        // --- Build required infrastructure to load the initial state, then initialize the States API ---
        final Time time = Time.getCurrent();
        final FileSystemManager fileSystemManager = FileSystemManager.create(platformConfig);
        final RecycleBin recycleBin =
                RecycleBin.create(metrics, platformConfig, getStaticThreadManager(), time, fileSystemManager, selfId);

        final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler = new DockerStateEventHandler();

        final PlatformContext platformContext = PlatformContext.create(
                platformConfig, Time.getCurrent(), metrics, fileSystemManager, recycleBin, merkleCryptography);

        final HashedReservedSignedState reservedState = loadInitialState(
                recycleBin,
                version,
                () -> TurtleAppState.createGenesisState(platformConfig, genesisRoster, version),
                APP_NAME,
                SWIRLD_NAME,
                selfId,
                platformStateFacade,
                platformContext);
        final ReservedSignedState initialState = reservedState.state();

        // --- Create the platform context and initialize the cryptography ---
        final MerkleNodeState state = initialState.get().getState();
        final RosterHistory rosterHistory = RosterUtils.createRosterHistory(state);

        // --- Now build the platform and start it ---
        platform = PlatformBuilder.create(
                        APP_NAME,
                        SWIRLD_NAME,
                        version,
                        initialState,
                        consensusStateEventHandler,
                        selfId,
                        selfId.toString(),
                        rosterHistory,
                        platformStateFacade)
                .withPlatformContext(platformContext)
                .withConfiguration(platformConfig)
                .withKeysAndCerts(keysAndCerts)
                .withSystemTransactionEncoderCallback(DockerApp::encodeSystemTransaction)
                .build();

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
     * Encodes a {@link StateSignatureTransaction}
     *
     * @param stateSignatureTransaction the transaction to encode
     * @return the encoded transaction as a {@link Bytes} object
     */
    private static Bytes encodeSystemTransaction(@NonNull final StateSignatureTransaction stateSignatureTransaction) {
        return Bytes.EMPTY; // FIXME
    }
}
