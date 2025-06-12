package org.hiero.consensus.otter.docker.app.platform;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.io.utility.FileUtils.rethrowIO;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_OVERRIDES_YAML_FILE_NAME;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_SETTINGS_FILE_NAME;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.initLogging;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.config.internal.PlatformConfigUtils.checkConfiguration;
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
import com.swirlds.config.extensions.sources.SystemEnvironmentConfigSource;
import com.swirlds.config.extensions.sources.SystemPropertiesConfigSource;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.crypto.KeyGeneratingException;
import com.swirlds.platform.crypto.KeysAndCertsGenerator;
import com.swirlds.platform.crypto.PublicStores;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.util.BootstrapUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.otter.fixtures.turtle.app.TurtleAppState;

public class DockerApp {
    private final static Logger LOGGER = LogManager.getLogger(DockerApp.class);

    private static final String APP_NAME = "org.hiero.consensus.otter.docker.app.platform.DockerApp";
    private static final String SWIRLD_NAME = "123";
    private static final byte[] EMPTY = new byte[0];

    private final Platform platform;

    public DockerApp()
            throws KeyStoreException, KeyGeneratingException, NoSuchAlgorithmException, NoSuchProviderException, ConstructableRegistryException {
        // --- Configure platform infrastructure and derive node id from the command line and environment ---
        initLogging();
        BootstrapUtils.setupConstructableRegistry();
        final Configuration platformConfig = buildPlatformConfig(); // TODO From CLI/Config/Rest?

        // Immediately initialize the cryptography and merkle cryptography factories
        // to avoid using default behavior instead of that defined in platformConfig
        final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(platformConfig);

        final Random random = new Random(); // TODO Should we do seeded random?
        final Roster genesisRoster = RandomRosterBuilder.create(random).withSize(4).build(); // TODO From CLI/Config/Rest?
        final NodeId selfId = NodeId.of(genesisRoster.rosterEntries().getFirst().nodeId()); // TODO From CLI/Config/Rest?

        // --- Initialize the platform metrics and the Hedera instance ---
        setupGlobalMetrics(platformConfig);
        final Metrics metrics = getMetricsProvider().createPlatformMetrics(selfId);
        final PlatformStateFacade platformStateFacade = new PlatformStateFacade();
        final SemanticVersion version = SemanticVersion.DEFAULT; // TODO: From CLI/Config/Rest?

        LOGGER.info("Starting node {} with version {}", selfId, version);

        // --- Build required infrastructure to load the initial state, then initialize the States API ---
        BootstrapUtils.setupConstructableRegistryWithConfiguration(platformConfig);
        final Time time = Time.getCurrent();
        final FileSystemManager fileSystemManager = FileSystemManager.create(platformConfig);
        final RecycleBin recycleBin =
                RecycleBin.create(metrics, platformConfig, getStaticThreadManager(), time, fileSystemManager, selfId);

        final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler = new DockerStateEventHandler();

        final PlatformContext platformContext = PlatformContext.create(
                platformConfig,
                Time.getCurrent(),
                metrics,
                FileSystemManager.create(platformConfig),
                recycleBin,
                merkleCryptography);

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

        final KeysAndCerts keysAndCerts = KeysAndCertsGenerator.generate(selfId, EMPTY, EMPTY, EMPTY, new PublicStores());

        // --- Now build the platform and start it ---
        final var platformBuilder = PlatformBuilder.create(
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
                .withSystemTransactionEncoderCallback(DockerApp::encodeSystemTransaction);

        platform = platformBuilder.build();
    }

    public Platform get() {
        return platform;
    }

    private static Bytes encodeSystemTransaction(@NonNull final StateSignatureTransaction stateSignatureTransaction) {
        return Bytes.EMPTY; // FIXME
    }

    @NonNull
    private static Configuration buildPlatformConfig() {
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSource(SystemEnvironmentConfigSource.getInstance())
                .withSource(SystemPropertiesConfigSource.getInstance());

        rethrowIO(() -> BootstrapUtils.setupConfigBuilder(
                configurationBuilder,
                getAbsolutePath(DEFAULT_SETTINGS_FILE_NAME),
                getAbsolutePath(DEFAULT_OVERRIDES_YAML_FILE_NAME)));
        final Configuration configuration = configurationBuilder.build();
        checkConfiguration(configuration);
        return configuration;
    }
}
