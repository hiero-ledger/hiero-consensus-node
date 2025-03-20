// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.io.utility.FileUtils.rethrowIO;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_CONFIG_FILE_NAME;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_OVERRIDES_YAML_FILE_NAME;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_SETTINGS_FILE_NAME;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.initLogging;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.config.internal.PlatformConfigUtils.checkConfiguration;
import static com.swirlds.platform.crypto.CryptoStatic.initNodeSecurity;
import static com.swirlds.platform.roster.RosterUtils.buildAddressBook;
import static com.swirlds.platform.state.signed.StartupStateUtils.copyInitialSignedState;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static com.swirlds.platform.system.InitTrigger.RESTART;
import static com.swirlds.platform.system.SystemExitCode.NODE_ADDRESS_MISMATCH;
import static com.swirlds.platform.system.SystemExitUtils.exitSystem;
import static com.swirlds.platform.util.BootstrapUtils.getNodesToRun;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.hints.impl.HintsServiceImpl;
import com.hedera.node.app.history.impl.HistoryLibraryImpl;
import com.hedera.node.app.history.impl.HistoryServiceImpl;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.services.OrderedServiceMigrator;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.node.app.state.ConsensusStateEventHandlerImpl;
import com.hedera.node.app.tss.TssBlockHashSigner;
import com.hedera.node.app.version.ServicesSoftwareVersion;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.RuntimeConstructable;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.CryptographyProvider;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.platform.NodeId;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SystemEnvironmentConfigSource;
import com.swirlds.config.extensions.sources.SystemPropertiesConfigSource;
import com.swirlds.logging.legacy.payload.SavedStateLoadedPayload;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.Browser;
import com.swirlds.platform.CommandLineArgs;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.config.legacy.ConfigurationException;
import com.swirlds.platform.config.legacy.LegacyConfigProperties;
import com.swirlds.platform.config.legacy.LegacyConfigPropertiesLoader;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.ReadableRosterStoreImpl;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.StartupStateUtils;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main entry point.
 *
 * <p>This class simply delegates to {@link Hedera}.
 */
public class ServicesMain implements SwirldMain<MerkleNodeState> {
    private static final Logger logger = LogManager.getLogger(ServicesMain.class);

    /**
     * A supplier that refuses to satisfy fallback requests for a set of node ids to run
     * simultaneously; this is only useful for certain platform testing applications
     */
    private static final Supplier<Set<NodeId>> ILLEGAL_FALLBACK_NODE_IDS = () -> {
        throw new IllegalStateException("The node id must be configured explicitly");
    };
    /**
     * Upfront validation on node ids is only useful for certain platform testing applications
     */
    private static final Predicate<NodeId> NOOP_NODE_VALIDATOR = nodeId -> true;

    /**
     * The {@link Hedera} singleton.
     */
    private static Hedera hedera;

    /**
     * The {@link Metrics} to use.
     */
    private static Metrics metrics;

    public ServicesMain() {
        // No-op, everything must be initialized in the main() entrypoint
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull SoftwareVersion getSoftwareVersion() {
        return hederaOrThrow().getSoftwareVersion();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(@NonNull final Platform platform, @NonNull final NodeId nodeId) {
        requireNonNull(platform);
        requireNonNull(nodeId);
        hederaOrThrow().init(platform, nodeId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull MerkleNodeState newStateRoot() {
        return hederaOrThrow().newStateRoot();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConsensusStateEventHandler<MerkleNodeState> newConsensusStateEvenHandler() {
        return new ConsensusStateEventHandlerImpl(hederaOrThrow());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        hederaOrThrow().run();
    }

    @Override
    public @NonNull Bytes encodeSystemTransaction(@NonNull StateSignatureTransaction transaction) {
        return hedera.encodeSystemTransaction(transaction);
    }

    /**
     * Launches Services directly, without use of the "app browser" from {@link Browser}. The
     * approximate startup sequence is:
     * <ol>
     *     <li>Scan the classpath for {@link RuntimeConstructable} classes,
     *     registering their no-op constructors as the default factories for their
     *     class ids.</li>
     *     <li>Create the application's {@link Hedera} singleton, which overrides
     *     the default factory for the stable {@literal 0x8e300b0dfdafbb1a} class
     *     id of the Services Merkle tree root with a reference to its
     *     {@link Hedera#newStateRoot()} method.</li>
     *     <li>Determine this node's <b>self id</b> by searching the <i>config.txt</i>
     *     in the working directory for any address book entries with IP addresses
     *     local to this machine; if there is more than one such entry, fail unless
     *     the command line args include a {@literal -local N} arg.</li>
     *     <li>Build a {@link Platform} instance from Services application metadata
     *     and the working directory <i>settings.txt</i>, providing the same
     *     {@link Hedera#newStateRoot()} method reference as the genesis state
     *     factory. (<b>IMPORTANT:</b> This step instantiates and invokes
     *     {@link ConsensusStateEventHandler#onStateInitialized(MerkleNodeState, Platform, InitTrigger, SemanticVersion)}
     *     on a {@link MerkleNodeState} instance that delegates the call back to our
     *     Hedera instance.)</li>
     *     <li>Call {@link Hedera#init(Platform, NodeId)} to complete startup phase
     *     validation and register notification listeners on the platform.</li>
     *     <li>Invoke {@link Platform#start()}.</li>
     * </ol>
     *
     * <p>Please see the <i>startup-phase-lifecycle.png</i> in this directory to visualize
     * the sequence of events in the startup phase and the centrality of the {@link Hedera}
     * singleton.
     * <p>
     * <b>IMPORTANT:</b> A surface-level reading of this method will undersell the centrality
     * of the Hedera instance. It is actually omnipresent throughout both the startup and
     * runtime phases of the application. Let's see why. When we build the platform, the
     * builder will either:
     * <ol>
     *      <li>Create a genesis state; or,</li>
     *      <li>Deserialize a saved state.</li>
     * </ol>
     * In both cases the state object will be created by the {@link Hedera#newStateRoot()}
     * method reference bound to our Hedera instance. Because,
     * <ol>
     *      <li>We provided this method as the genesis state factory right above; and,</li>
     *      <li>Our Hedera instance's constructor registered its {@link Hedera#newStateRoot()}
     *      method with the {@link ConstructableRegistry} as the factory for the Services state root
     *      class id.</li>
     * </ol>
     *  Now, note that {@link Hedera#newStateRoot()} returns {@link MerkleNodeState}
     *  instances that delegate their lifecycle methods to an injected instance of
     *  {@link ConsensusStateEventHandler}---and the implementation of that
     *  injected by {@link Hedera#newStateRoot()} delegates these calls back to the Hedera
     *  instance itself.
     *  <p>
     *  Thus, the Hedera instance centralizes nearly all the setup and runtime logic for the
     *  application. It implements this logic by instantiating a {@link javax.inject.Singleton}
     *  component whose object graph roots include the Ingest, PreHandle, Handle, and Query
     *  workflows; as well as other infrastructure components that need to be initialized or
     *  accessed at specific points in the Swirlds application lifecycle.
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
            exitSystem(NODE_ADDRESS_MISMATCH);
            // the following throw is not reachable in production,
            // but reachable in testing with static mocked system exit calls.
            throw new ConfigurationException();
        }
        final var platformConfig = buildPlatformConfig();
        // Immediately initialize the cryptography and merkle cryptography factories
        // to avoid using default behavior instead of that defined in platformConfig
        final var cryptography = CryptographyProvider.getInstance();
        final var merkleCryptography = MerkleCryptographyFactory.create(platformConfig);

        // Determine which nodes were _requested_ to run from the command line
        final var cliNodesToRun = commandLineArgs.localNodesToStart();
        // Determine which nodes are _configured_ to run from the config file(s)
        final var configNodesToRun =
                platformConfig.getConfigData(BasicConfig.class).nodesToRun();
        // Using the requested nodes to run from the command line, the nodes configured to run, and now the
        // address book on disk, reconcile the list of nodes to run
        final List<NodeId> nodesToRun =
                getNodesToRun(cliNodesToRun, configNodesToRun, ILLEGAL_FALLBACK_NODE_IDS, NOOP_NODE_VALIDATOR);
        // Finally, verify that the reconciliation of above node IDs yields exactly one node to run
        final var selfId = ensureSingleNode(nodesToRun);

        // --- Initialize the platform metrics and the Hedera instance ---
        setupGlobalMetrics(platformConfig);
        metrics = getMetricsProvider().createPlatformMetrics(selfId);
        final PlatformStateFacade platformStateFacade = new PlatformStateFacade(ServicesSoftwareVersion::new);
        hedera = newHedera(metrics, platformStateFacade);
        final var version = hedera.getSoftwareVersion();
        final AtomicReference<Network> genesisNetwork = new AtomicReference<>();
        logger.info("Starting node {} with version {}", selfId, version);

        // --- Build required infrastructure to load the initial state, then initialize the States API ---
        BootstrapUtils.setupConstructableRegistryWithConfiguration(platformConfig);
        final var time = Time.getCurrent();
        final var fileSystemManager = FileSystemManager.create(platformConfig);
        final var recycleBin =
                RecycleBin.create(metrics, platformConfig, getStaticThreadManager(), time, fileSystemManager, selfId);
        ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler = hedera.newConsensusStateEvenHandler();
        final PlatformContext platformContext = PlatformContext.create(
                platformConfig,
                Time.getCurrent(),
                metrics,
                FileSystemManager.create(platformConfig),
                recycleBin,
                merkleCryptography);
        final Optional<AddressBook> maybeDiskAddressBook = loadLegacyAddressBook();
        final HashedReservedSignedState reservedState = loadInitialState(
                platformConfig,
                recycleBin,
                version.getPbjSemanticVersion(),
                () -> {
                    Network network;
                    try {
                        network = hedera.startupNetworks().genesisNetworkOrThrow(platformConfig);
                    } catch (Exception ignore) {
                        // Fallback to the legacy address book if genesis-network.json or equivalent not loaded
                        network = DiskStartupNetworks.fromLegacyAddressBook(maybeDiskAddressBook.orElseThrow());
                    }
                    genesisNetwork.set(network);
                    final var genesisState = hedera.newStateRoot();
                    hedera.initializeStatesApi(genesisState, GENESIS, platformConfig);
                    return genesisState;
                },
                Hedera.APP_NAME,
                Hedera.SWIRLD_NAME,
                selfId,
                platformStateFacade,
                platformContext);
        final ReservedSignedState initialState = reservedState.state();
        final MerkleNodeState state = initialState.get().getState();
        if (genesisNetwork.get() == null) {
            hedera.initializeStatesApi(state, RESTART, platformConfig);
        }
        hedera.setInitialStateHash(reservedState.hash());

        // --- Create the platform context and initialize the cryptography ---
        final var rosterStore = new ReadableRosterStoreImpl(state.getReadableStates(RosterService.NAME));
        final var currentRoster = requireNonNull(rosterStore.getActiveRoster());
        // For now we convert to a legacy representation of the roster for convenience
        final var addressBook = requireNonNull(buildAddressBook(currentRoster));
        if (!addressBook.contains(selfId)) {
            throw new IllegalStateException("Self node id " + selfId + " is not in the address book");
        }
        final var networkKeysAndCerts = initNodeSecurity(addressBook, platformConfig, Set.copyOf(nodesToRun));
        final var keysAndCerts = networkKeysAndCerts.get(selfId);
        cryptography.digestSync(addressBook);

        // --- Now build the platform and start it ---
        final var rosterHistory = RosterUtils.createRosterHistory(rosterStore);
        final var platformBuilder = PlatformBuilder.create(
                        Hedera.APP_NAME,
                        Hedera.SWIRLD_NAME,
                        version,
                        initialState,
                        consensusStateEventHandler,
                        selfId,
                        // If at genesis, base the event stream location on the genesis network metadata
                        Optional.ofNullable(genesisNetwork.get())
                                .map(network -> eventStreamLocOrThrow(network, selfId.id()))
                                // Otherwise derive if from the node's id in state or
                                .orElseGet(() -> canonicalEventStreamLoc(selfId.id(), state)),
                        rosterHistory,
                        platformStateFacade)
                .withPlatformContext(platformContext)
                .withConfiguration(platformConfig)
                .withKeysAndCerts(keysAndCerts)
                .withSystemTransactionEncoderCallback(hedera::encodeSystemTransaction);
        final var platform = platformBuilder.build();
        hedera.init(platform, selfId);

        // Initialize block node connections before starting the platform
        final var waitPeriodForActiveConnection =
                platformConfig.getConfigData(BlockStreamConfig.class).waitPeriodForActiveConnection();
        hedera.initializeBlockNodeConnections(Duration.ofMinutes(waitPeriodForActiveConnection));

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
        } catch (Exception ignore) {
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
     * @param metrics  the metrics
     * @param platformStateFacade an object to access the platform state
     * @return the {@link Hedera} instance
     */
    public static Hedera newHedera(
            @NonNull final Metrics metrics, @NonNull final PlatformStateFacade platformStateFacade) {
        requireNonNull(metrics);
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
                        metrics, ForkJoinPool.commonPool(), appContext, new HistoryLibraryImpl(), bootstrapConfig),
                TssBlockHashSigner::new,
                metrics,
                platformStateFacade);
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

    /**
     * Ensures there is exactly 1 node to run.
     *
     * @param nodesToRun        the list of nodes configured to run.
     * @return the node which should be run locally.
     * @throws ConfigurationException if more than one node would be started or the requested node is not configured.
     */
    private static NodeId ensureSingleNode(@NonNull final List<NodeId> nodesToRun) {
        requireNonNull(nodesToRun);

        logger.info(STARTUP.getMarker(), "The following nodes {} are set to run locally", nodesToRun);
        if (nodesToRun.isEmpty()) {
            final String errorMessage = "No nodes are configured to run locally.";
            logger.error(STARTUP.getMarker(), errorMessage);
            exitSystem(NODE_ADDRESS_MISMATCH, errorMessage);
            // the following throw is not reachable in production,
            // but reachable in testing with static mocked system exit calls.
            throw new ConfigurationException(errorMessage);
        }

        if (nodesToRun.size() > 1) {
            final String errorMessage = "Multiple nodes are configured to run locally.";
            logger.error(EXCEPTION.getMarker(), errorMessage);
            exitSystem(NODE_ADDRESS_MISMATCH, errorMessage);
            // the following throw is not reachable in production,
            // but reachable in testing with static mocked system exit calls.
            throw new ConfigurationException(errorMessage);
        }
        return nodesToRun.getFirst();
    }

    /**
     * Loads the legacy address book if it is present. Can be removed once no environment relies on using
     * legacy <i>config.txt</i> as a startup asset.
     * @return the address book from a legacy config file, if present
     */
    @Deprecated
    private static Optional<AddressBook> loadLegacyAddressBook() {
        try {
            final LegacyConfigProperties props =
                    LegacyConfigPropertiesLoader.loadConfigFile(getAbsolutePath(DEFAULT_CONFIG_FILE_NAME));
            props.appConfig().ifPresent(c -> ParameterProvider.getInstance().setParameters(c.params()));
            return Optional.of(props.getAddressBook());
        } catch (final Exception ignore) {
            return Optional.empty();
        }
    }

    /**
     * Get the initial state to be used by this node. May return a state loaded from disk, or may return a genesis state
     * if no valid state is found on disk.
     *
     * @param configuration      the configuration for this node
     * @param softwareVersion     the software version of the app
     * @param stateRootSupplier a supplier that can build a genesis state
     * @param mainClassName       the name of the app's SwirldMain class
     * @param swirldName          the name of this swirld
     * @param selfId              the node id of this node
     * @param platformStateFacade an object to access the platform state
     * @return the initial state to be used by this node
     */
    @NonNull
    private static HashedReservedSignedState loadInitialState(
            @NonNull final Configuration configuration,
            @NonNull final RecycleBin recycleBin,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final Supplier<MerkleNodeState> stateRootSupplier,
            @NonNull final String mainClassName,
            @NonNull final String swirldName,
            @NonNull final NodeId selfId,
            @NonNull final PlatformStateFacade platformStateFacade,
            @NonNull final PlatformContext platformContext) {
        final var loadedState = StartupStateUtils.loadStateFile(
                recycleBin, selfId, mainClassName, swirldName, softwareVersion, platformStateFacade, platformContext);
        try (loadedState) {
            if (loadedState.isNotNull()) {
                logger.info(
                        STARTUP.getMarker(),
                        new SavedStateLoadedPayload(
                                loadedState.get().getRound(), loadedState.get().getConsensusTimestamp()));
                return copyInitialSignedState(loadedState.get(), platformStateFacade, platformContext);
            }
        }
        final var stateRoot = stateRootSupplier.get();
        final var signedState = new SignedState(
                configuration,
                CryptoStatic::verifySignature,
                stateRoot,
                "genesis state",
                false,
                false,
                false,
                platformStateFacade);
        signedState.init(platformContext);
        final var reservedSignedState = signedState.reserve("initial reservation on genesis state");
        try (reservedSignedState) {
            return copyInitialSignedState(reservedSignedState.get(), platformStateFacade, platformContext);
        }
    }

    private static @NonNull Hedera hederaOrThrow() {
        return requireNonNull(hedera);
    }

    @VisibleForTesting
    static void initGlobal(@NonNull final Hedera hedera, @NonNull final Metrics metrics) {
        ServicesMain.hedera = hedera;
        ServicesMain.metrics = metrics;
    }
}
