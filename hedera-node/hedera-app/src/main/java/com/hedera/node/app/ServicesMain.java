// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.io.utility.FileUtils.rethrowIO;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_OVERRIDES_YAML_FILE_NAME;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_SETTINGS_FILE_NAME;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.initLogging;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.config.internal.PlatformConfigUtils.checkConfiguration;
import static com.swirlds.platform.crypto.CryptoStatic.initNodeSecurity;
import static com.swirlds.platform.state.signed.StartupStateUtils.loadInitialState;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static com.swirlds.platform.system.InitTrigger.RESTART;
import static com.swirlds.platform.system.SystemExitCode.NODE_ID_NOT_PROVIDED;
import static com.swirlds.platform.system.SystemExitUtils.exitSystem;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.hints.impl.HintsServiceImpl;
import com.hedera.node.app.history.impl.HistoryLibraryImpl;
import com.hedera.node.app.history.impl.HistoryServiceImpl;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.ReadableEntityIdStoreImpl;
import com.hedera.node.app.services.OrderedServiceMigrator;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.app.tss.TssBlockHashSigner;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBinImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SystemEnvironmentConfigSource;
import com.swirlds.config.extensions.sources.SystemPropertiesConfigSource;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.CommandLineArgs;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.config.legacy.ConfigurationException;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.RuntimeConstructable;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterStateUtils;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Main entry point.
 *
 * <p>This class simply delegates to {@link Hedera}.
 */
public class ServicesMain {
    private static final Logger logger = LogManager.getLogger(ServicesMain.class);

    /**
     * The {@link Hedera} singleton.
     */
    private static Hedera hedera;

    /**
     * The {@link Metrics} to use.
     */
    private static Metrics metrics;

    /**
     * Launches Services: the approximate startup sequence is:
     * <ol>
     *     <li>Scan the classpath for {@link RuntimeConstructable} classes,
     *     registering their no-op constructors as the default factories for their
     *     class ids.</li>
     *     <li>Create the application's {@link Hedera} singleton, which initializes an instance of
     *     {@link StateLifecycleManagerImpl} to manage state instances.</li>
     *     <li>Determine this node's <b>self id</b> by searching the <i>config.txt</i>
     *     in the working directory for any address book entries with IP addresses
     *     local to this machine; if there is more than one such entry, fail unless
     *     the command line args include a {@literal -local N} arg.</li>
     *     <li>Load the initial state via the {@code StateLifecycleManager}, which creates
     *     a genesis state eagerly in its constructor if no saved state is found.
     *     (<b>IMPORTANT:</b> This step instantiates and invokes
     *     {@link ConsensusStateEventHandler#onStateInitialized(State, Platform, InitTrigger, SemanticVersion)}
     *     on a {@link VirtualMapState} instance that delegates the call back to our
     *     Hedera instance.)</li>
     *     <li>Invoke {@link Platform#start()}.</li>
     * </ol>
     *
     * <p>Please see the <i>startup-phase-lifecycle.png</i> in this directory to visualize
     * the sequence of events in the startup phase and the centrality of the {@link Hedera}
     * singleton.
     * <p>
     * <b>IMPORTANT:</b> A surface-level reading of this method will undersell the centrality
     * of the Hedera instance. It is actually omnipresent throughout both the startup and
     * runtime phases of the application. The {@link StateLifecycleManager} owned by the
     * Hedera instance is responsible for creating and managing all state instances. The
     * Hedera instance centralizes nearly all the setup and runtime logic for the application.
     * It implements this logic by instantiating a {@link javax.inject.Singleton} component
     * whose object graph roots include the Ingest, PreHandle, Handle, and Query workflows;
     * as well as other infrastructure components that need to be initialized or accessed at
     * specific points in the Swirlds application lifecycle.
     *
     * @param args optionally, what node id to run; required if the address book is ambiguous
     */
    public static void main(final String... args) throws Exception {
        // --- Configure platform infrastructure and derive node id from the command line and environment ---
        initLogging();
        BootstrapUtils.setupConstructableRegistry();
        final var commandLineArgs = CommandLineArgs.parse(args);
        if (commandLineArgs.localNodesToStart().size() > 1) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Multiple nodes were supplied via the command line. Only one node can be started per java process.");
            exitSystem(NODE_ID_NOT_PROVIDED);
            // the following throw is not reachable in production,
            // but reachable in testing with static mocked system exit calls.
            throw new ConfigurationException();
        }
        final var platformConfig = buildPlatformConfig();

        final var selfId = commandLineArgs.localNodesToStart().stream()
                .findFirst()
                .orElseThrow(() -> {
                    final String msg = "No node id specified on command line. Use -local <nodeId>";
                    exitSystem(NODE_ID_NOT_PROVIDED, msg);
                    return new ConfigurationException(msg);
                });

        // --- Initialize the platform metrics and the Hedera instance ---
        setupGlobalMetrics(platformConfig);
        final var time = Time.getCurrent();
        metrics = getMetricsProvider().createPlatformMetrics(selfId);
        hedera = newHedera(platformConfig, metrics, time);
        final var version = hedera.getSemanticVersion();
        logger.info("Starting node {} with version {}", selfId, version);

        // --- Build required infrastructure to load the initial state, then initialize the States API ---
        final var fileSystemManager = FileSystemManager.create(platformConfig);
        final var recycleBin = RecycleBinImpl.create(
                metrics, platformConfig, getStaticThreadManager(), time, fileSystemManager, selfId);
        final ConsensusStateEventHandler consensusStateEventHandler = hedera.newConsensusStateEvenHandler();
        final PlatformContext platformContext =
                PlatformContext.create(platformConfig, Time.getCurrent(), metrics, fileSystemManager, recycleBin);

        // Try to load a saved state from disk. The StateLifecycleManager creates a genesis state eagerly in its
        // constructor, so if no saved state is found we proceed with the genesis path.
        final HashedReservedSignedState reservedState = loadInitialState(
                recycleBin,
                version,
                Hedera.APP_NAME,
                Hedera.SWIRLD_NAME,
                selfId,
                platformContext,
                hedera.getStateLifecycleManager());
        final ReservedSignedState initialState = reservedState.state();
        final VirtualMapState state = initialState.get().getState();

        // Determine whether we are starting from genesis or restarting from a saved state.
        // A genesis state has no creation software version set in the platform state.
        final boolean isGenesis = initialState.get().isGenesisState();

        if (isGenesis) {
            // Genesis path: initialize the States API on the genesis state created by the manager.
            hedera.initializeStatesApi(state, GENESIS, platformConfig);
        } else {
            // Restart path: initialize the States API on the loaded state.
            hedera.initializeStatesApi(state, RESTART, platformConfig);
        }
        hedera.setInitialStateHash(reservedState.hash());
        logger.info(
                "Initial state hash: {}",
                reservedState.hash() != null ? reservedState.hash().toHex() : "<null>");

        final RosterHistory rosterHistory;
        final List<RosterEntry> rosterEntries;
        if (isGenesis) {
            final var genesisRoster = hedera.genesisRosterOrThrow();
            rosterHistory = RosterHistory.fromGenesis(genesisRoster);
            rosterEntries = genesisRoster.rosterEntries();
        } else {
            rosterHistory = RosterStateUtils.createRosterHistory(state);
            final var rosterStore = new ReadableStoreFactoryImpl(state).readableStore(ReadableRosterStore.class);
            rosterEntries = requireNonNull(rosterStore.getActiveRoster()).rosterEntries();
        }
        final var keysAndCerts = initNodeSecurity(platformConfig, selfId, rosterEntries);

        final String consensusEventStreamName = isGenesis
                // If at genesis, base the event stream location on the genesis network metadata
                ? eventStreamLocOrThrow(hedera.startupNetworks().genesisNetworkOrThrow(platformConfig), selfId.id())
                // Otherwise derive it from the node's id in state
                : canonicalEventStreamLoc(selfId.id(), state);
        // --- Now build the platform and start it ---
        final var platformBuilder = PlatformBuilder.create(
                        Hedera.APP_NAME,
                        Hedera.SWIRLD_NAME,
                        version,
                        initialState,
                        consensusStateEventHandler,
                        selfId,
                        consensusEventStreamName,
                        rosterHistory,
                        hedera.getStateLifecycleManager())
                .withPlatformContext(platformContext)
                .withConfiguration(platformConfig)
                .withKeysAndCerts(keysAndCerts)
                .withExecutionLayer(hedera)
                .withStaleEventCallback(hedera);
        final var platform = platformBuilder.build();
        platform.start();
        hedera.run();
    }

    /**
     * Returns the event stream location for the given node id based on the given network metadata.
     * @param network the network metadata
     * @param nodeId the node id
     * @return the event stream location
     */
    private static String eventStreamLocOrThrow(@NonNull final Network network, final long nodeId) {
        return network.nodeMetadata().stream()
                .map(NodeMetadata::nodeOrThrow)
                .filter(node -> node.nodeId() == nodeId)
                .map(node -> canonicalEventStreamLoc(node.accountIdOrThrow()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Returns the event stream name for the given node id.
     *
     * @param nodeId the node id
     * @param root the platform merkle state root
     * @return the event stream name
     */
    private static String canonicalEventStreamLoc(final long nodeId, @NonNull final State root) {
        try {
            final var nodeStore = new ReadableNodeStoreImpl(
                    root.getReadableStates(AddressBookService.NAME),
                    new ReadableEntityIdStoreImpl(root.getReadableStates(EntityIdService.NAME)));
            final var accountId = requireNonNull(nodeStore.get(nodeId)).accountIdOrThrow();
            return canonicalEventStreamLoc(accountId);
        } catch (final Exception ignore) {
            // If this node id was not in the state address book, as a final fallback assume
            // we are restarting from round zero state and try to use genesis startup assets,
            // which are not archived until at least one round has been handled
            final var genesisNetwork =
                    hederaOrThrow().genesisNetworkSupplierOrThrow().get();
            return eventStreamLocOrThrow(genesisNetwork, nodeId);
        }
    }

    /**
     * Returns the event stream name for the given account id.
     * @return the event stream name
     */
    private static String canonicalEventStreamLoc(@NonNull final AccountID accountId) {
        requireNonNull(accountId);
        return accountId.shardNum() + "." + accountId.realmNum() + "." + accountId.accountNumOrThrow();
    }

    /**
     * Creates a canonical {@link Hedera} instance for the given node id and metrics.
     *
     * @param configuration the platform configuration instance to use when creating the new instance of state
     * @param metrics       the platform metric instance to use when creating the new instance of state
     * @param time          the time instance to use when creating the new instance of state
     * @return the {@link Hedera} instance
     */
    public static Hedera newHedera(
            @NonNull final Configuration configuration, @NonNull final Metrics metrics, @NonNull final Time time) {
        requireNonNull(configuration);
        requireNonNull(metrics);
        requireNonNull(time);
        return new Hedera(
                ConstructableRegistry.getInstance(),
                ServicesRegistryImpl::new,
                new OrderedServiceMigrator(),
                InstantSource.system(),
                DiskStartupNetworks::new,
                (appContext, bootstrapConfig) -> new HintsServiceImpl(
                        metrics,
                        ForkJoinPool.commonPool(),
                        appContext,
                        new HintsLibraryImpl(),
                        bootstrapConfig.getConfigData(BlockStreamConfig.class).blockPeriod()),
                (appContext, bootstrapConfig) -> new HistoryServiceImpl(
                        metrics, ForkJoinPool.commonPool(), appContext, new HistoryLibraryImpl()),
                TssBlockHashSigner::new,
                configuration,
                metrics,
                time);
    }

    /**
     * Builds the platform configuration for this node.
     *
     * @return the configuration
     */
    @NonNull
    public static Configuration buildPlatformConfig() {
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

    private static @NonNull Hedera hederaOrThrow() {
        return requireNonNull(hedera);
    }
}
