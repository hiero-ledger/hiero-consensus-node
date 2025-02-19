/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.io.utility.FileUtils.rethrowIO;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_CONFIG_FILE_NAME;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_SETTINGS_FILE_NAME;
import static com.swirlds.platform.builder.PlatformBuildConstants.LOG4J_FILE_NAME;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.config.internal.PlatformConfigUtils.checkConfiguration;
import static com.swirlds.platform.crypto.CryptoStatic.initNodeSecurity;
import static com.swirlds.platform.roster.RosterUtils.buildRosterHistory;
import static com.swirlds.platform.state.signed.StartupStateUtils.copyInitialSignedState;
import static com.swirlds.platform.system.SystemExitCode.CONFIGURATION_ERROR;
import static com.swirlds.platform.system.SystemExitCode.NODE_ADDRESS_MISMATCH;
import static com.swirlds.platform.system.SystemExitUtils.exitSystem;
import static com.swirlds.platform.util.BootstrapUtils.checkNodesToRun;
import static com.swirlds.platform.util.BootstrapUtils.detectSoftwareUpgrade;
import static com.swirlds.platform.util.BootstrapUtils.getNodesToRun;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.services.OrderedServiceMigrator;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.tss.TssBaseServiceImpl;
import com.hedera.node.app.tss.TssLibraryImpl;
import com.swirlds.base.time.Time;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.RuntimeConstructable;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.CryptographyFactory;
import com.swirlds.common.crypto.CryptographyHolder;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptoFactory;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.startup.Log4jSetup;
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
import com.swirlds.platform.config.legacy.ConfigurationException;
import com.swirlds.platform.config.legacy.LegacyConfigProperties;
import com.swirlds.platform.config.legacy.LegacyConfigPropertiesLoader;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.roster.RosterHistory;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.MerkleRoot;
import com.swirlds.platform.state.PlatformMerkleStateRoot;
import com.swirlds.platform.state.address.AddressBookInitializer;
import com.swirlds.platform.state.service.ReadableRosterStore;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.StartupStateUtils;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.platform.system.SwirldState;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.state.State;
import com.swirlds.state.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main entry point.
 *
 * <p>This class simply delegates to {@link Hedera}.
 */
public class ServicesMain implements SwirldMain {
    private static final Logger logger = LogManager.getLogger(ServicesMain.class);

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
    public @NonNull MerkleRoot newMerkleStateRoot() {
        return hederaOrThrow().newMerkleStateRoot();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        hederaOrThrow().run();
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
     *     {@link Hedera#newMerkleStateRoot()} method.</li>
     *     <li>Determine this node's <b>self id</b> by searching the <i>config.txt</i>
     *     in the working directory for any address book entries with IP addresses
     *     local to this machine; if there is more than one such entry, fail unless
     *     the command line args include a {@literal -local N} arg.</li>
     *     <li>Build a {@link Platform} instance from Services application metadata
     *     and the working directory <i>settings.txt</i>, providing the same
     *     {@link Hedera#newMerkleStateRoot()} method reference as the genesis state
     *     factory. (<b>IMPORTANT:</b> This step instantiates and invokes
     *     {@link SwirldState#init(Platform, InitTrigger, SoftwareVersion)}
     *     on a {@link MerkleStateRoot} instance that delegates the call back to our
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
     * In both cases the state object will be created by the {@link Hedera#newMerkleStateRoot()}
     * method reference bound to our Hedera instance. Because,
     * <ol>
     *      <li>We provided this method as the genesis state factory right above; and,</li>
     *      <li>Our Hedera instance's constructor registered its {@link Hedera#newMerkleStateRoot()}
     *      method with the {@link ConstructableRegistry} as the factory for the Services state root
     *      class id.</li>
     * </ol>
     *  Now, note that {@link Hedera#newMerkleStateRoot()} returns {@link PlatformMerkleStateRoot}
     *  instances that delegate their lifecycle methods to an injected instance of
     *  {@link com.swirlds.platform.state.MerkleStateLifecycles}---and the implementation of that
     *  injected by {@link Hedera#newMerkleStateRoot()} delegates these calls back to the Hedera
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
        initLogging();
        // --- Configure platform infrastructure and context from the command line and environment ---
        BootstrapUtils.setupConstructableRegistry();
        final var diskAddressBook = loadAddressBook(DEFAULT_CONFIG_FILE_NAME);
        final var commandLineArgs = CommandLineArgs.parse(args);
        if (commandLineArgs.localNodesToStart().size() > 1) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Multiple nodes were supplied via the command line. Only one node can be started per java process.");
            exitSystem(NODE_ADDRESS_MISMATCH);
        }
        final List<NodeId> nodesToRun = getNodesToRun(diskAddressBook, commandLineArgs.localNodesToStart());
        checkNodesToRun(nodesToRun);
        final var selfId = ensureSingleNode(nodesToRun, commandLineArgs.localNodesToStart());
        final var platformConfig = buildPlatformConfig();
        BootstrapUtils.setupConstructableRegistryWithConfiguration(platformConfig);
        final var networkKeysAndCerts = initNodeSecurity(diskAddressBook, platformConfig, Set.copyOf(nodesToRun));
        final var keysAndCerts = networkKeysAndCerts.get(selfId);
        setupGlobalMetrics(platformConfig);
        metrics = getMetricsProvider().createPlatformMetrics(selfId);
        final var time = Time.getCurrent();
        final var fileSystemManager = FileSystemManager.create(platformConfig);
        final var recycleBin =
                RecycleBin.create(metrics, platformConfig, getStaticThreadManager(), time, fileSystemManager, selfId);
        final var cryptography = CryptographyFactory.create();
        CryptographyHolder.set(cryptography);
        cryptography.digestSync(diskAddressBook);
        final var merkleCryptography = MerkleCryptographyFactory.create(platformConfig, cryptography);
        MerkleCryptoFactory.set(merkleCryptography);
        final var platformContext = PlatformContext.create(
                platformConfig,
                Time.getCurrent(),
                metrics,
                cryptography,
                FileSystemManager.create(platformConfig),
                recycleBin,
                merkleCryptography);

        // --- Construct the Hedera instance and use it to initialize the starting state ---
        hedera = newHedera(selfId, metrics);
        final var version = hedera.getSoftwareVersion();
        final var isGenesis = new AtomicBoolean(false);
        logger.info("Starting node {} with version {}", selfId, version);
        final var reservedState = loadInitialState(
                platformConfig,
                recycleBin,
                version,
                () -> {
                    isGenesis.set(true);
                    final var genesisState = (PlatformMerkleStateRoot) hedera.newMerkleStateRoot();
                    final var genesisNetwork = DiskStartupNetworks.fromLegacyAddressBook(diskAddressBook);
                    hedera.initializeStatesApi(
                            genesisState,
                            metrics,
                            InitTrigger.GENESIS,
                            genesisNetwork,
                            platformConfig,
                            diskAddressBook);
                    return genesisState;
                },
                Hedera.APP_NAME,
                Hedera.SWIRLD_NAME,
                selfId);
        final var initialState = reservedState.state();
        if (!isGenesis.get()) {
            hedera.initializeStatesApi(
                    (PlatformMerkleStateRoot) initialState.get().getState().getSwirldState(),
                    metrics,
                    InitTrigger.RESTART,
                    null,
                    platformConfig,
                    diskAddressBook);
        }
        hedera.setInitialStateHash(reservedState.hash());

        // --- Now build the platform and start it ---
        final var stateRoot = (PlatformMerkleStateRoot) initialState.get().getState();
        final RosterHistory rosterHistory;
        if (hedera.isRosterLifecycleEnabled()) {
            final var rosterStore = new ReadableStoreFactory(stateRoot).getStore(ReadableRosterStore.class);
            rosterHistory = RosterUtils.createRosterHistory(rosterStore);
        } else {
            // This constructor both does extensive validation and has the side effect of
            // moving unused config.txt files to an archive directory; so keep calling it
            // here until we enable the roster lifecycle
            new AddressBookInitializer(
                    selfId,
                    version,
                    detectSoftwareUpgrade(version, initialState.get()),
                    initialState.get(),
                    diskAddressBook.copy(),
                    platformContext);
            rosterHistory = buildRosterHistory((State) initialState.get().getState());
        }
        final var platformBuilder = PlatformBuilder.create(
                        Hedera.APP_NAME,
                        Hedera.SWIRLD_NAME,
                        version,
                        initialState,
                        selfId,
                        canonicalEventStreamLoc(selfId.id(), stateRoot),
                        rosterHistory)
                .withPlatformContext(platformContext)
                .withConfiguration(platformConfig)
                .withKeysAndCerts(keysAndCerts);
        final var platform = platformBuilder.build();
        hedera.init(platform, selfId);
        platform.start();
        hedera.run();
    }

    /**
     * Returns the event stream name for the given node id.
     *
     * @param nodeId the node id
     * @param root the platform merkle state root
     * @return the event stream name
     */
    private static String canonicalEventStreamLoc(final long nodeId, @NonNull final PlatformMerkleStateRoot root) {
        final var nodeStore = new ReadableNodeStoreImpl(root.getReadableStates(AddressBookService.NAME));
        final var accountId = requireNonNull(nodeStore.get(nodeId)).accountIdOrThrow();
        return accountId.shardNum() + "." + accountId.realmNum() + "." + accountId.accountNumOrThrow();
    }

    private static void initLogging() {
        final var log4jPath = getAbsolutePath(LOG4J_FILE_NAME);
        try {
            Log4jSetup.startLoggingFramework(log4jPath).await();
        } catch (final InterruptedException e) {
            // since the logging framework has not been instantiated, also log to stderr
            e.printStackTrace();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for log4j to initialize", e);
        }
    }

    /**
     * Creates a canonical {@link Hedera} instance for the given node id and metrics.
     *
     * @param selfNodeId the node id
     * @param metrics  the metrics
     * @return the {@link Hedera} instance
     */
    public static Hedera newHedera(@NonNull final NodeId selfNodeId, @NonNull final Metrics metrics) {
        requireNonNull(selfNodeId);
        requireNonNull(metrics);
        return new Hedera(
                ConstructableRegistry.getInstance(),
                ServicesRegistryImpl::new,
                new OrderedServiceMigrator(),
                InstantSource.system(),
                appContext -> new TssBaseServiceImpl(
                        appContext,
                        ForkJoinPool.commonPool(),
                        ForkJoinPool.commonPool(),
                        new TssLibraryImpl(appContext),
                        ForkJoinPool.commonPool(),
                        metrics),
                DiskStartupNetworks::new);
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
        rethrowIO(() ->
                BootstrapUtils.setupConfigBuilder(configurationBuilder, getAbsolutePath(DEFAULT_SETTINGS_FILE_NAME)));
        final Configuration configuration = configurationBuilder.build();
        checkConfiguration(configuration);
        return configuration;
    }

    /**
     * Selects the node to run locally from either the command line arguments or the address book.
     *
     * @param nodesToRun        the list of nodes configured to run based on the address book.
     * @param localNodesToStart the node ids specified on the command line.
     * @return the node which should be run locally.
     * @throws ConfigurationException if more than one node would be started or the requested node is not configured.
     */
    private static NodeId ensureSingleNode(
            @NonNull final List<NodeId> nodesToRun, @NonNull final Set<NodeId> localNodesToStart) {
        requireNonNull(nodesToRun);
        requireNonNull(localNodesToStart);
        // If no node is specified on the command line and detection by AB IP address is ambiguous, exit.
        if (nodesToRun.size() > 1 && localNodesToStart.isEmpty()) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Multiple nodes are configured to run. Only one node can be started per java process.");
            exitSystem(NODE_ADDRESS_MISMATCH);
            throw new ConfigurationException(
                    "Multiple nodes are configured to run. Only one node can be started per java process.");
        }

        // If a node is specified on the command line, use that node.
        final NodeId requestedNodeId = localNodesToStart.stream().findFirst().orElse(null);

        // If a node is specified on the command line but does not have a matching local IP address in the AB, exit.
        if (nodesToRun.size() > 1 && !nodesToRun.contains(requestedNodeId)) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "The requested node id {} is not configured to run. Please check the address book.",
                    requestedNodeId);
            exitSystem(NODE_ADDRESS_MISMATCH);
            throw new ConfigurationException(String.format(
                    "The requested node id %s is not configured to run. Please check the address book.",
                    requestedNodeId));
        }

        // Return either the node requested via the command line or the only matching node from the AB.
        return requestedNodeId != null ? requestedNodeId : nodesToRun.get(0);
    }

    /**
     * Loads the address book from the specified path.
     *
     * @param addressBookPath the relative path and file name of the address book.
     * @return the address book.
     */
    private static AddressBook loadAddressBook(@NonNull final String addressBookPath) {
        requireNonNull(addressBookPath);
        try {
            final LegacyConfigProperties props =
                    LegacyConfigPropertiesLoader.loadConfigFile(getAbsolutePath(addressBookPath));
            props.appConfig().ifPresent(c -> ParameterProvider.getInstance().setParameters(c.params()));
            return props.getAddressBook();
        } catch (final Exception e) {
            logger.error(EXCEPTION.getMarker(), "Error loading address book", e);
            exitSystem(CONFIGURATION_ERROR);
            throw e;
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
     * @return the initial state to be used by this node
     */
    @NonNull
    private static HashedReservedSignedState loadInitialState(
            @NonNull final Configuration configuration,
            @NonNull final RecycleBin recycleBin,
            @NonNull final SoftwareVersion softwareVersion,
            @NonNull final Supplier<MerkleRoot> stateRootSupplier,
            @NonNull final String mainClassName,
            @NonNull final String swirldName,
            @NonNull final NodeId selfId) {
        final var loadedState = StartupStateUtils.loadStateFile(
                configuration, recycleBin, selfId, mainClassName, swirldName, softwareVersion);
        try (loadedState) {
            if (loadedState.isNotNull()) {
                logger.info(
                        STARTUP.getMarker(),
                        new SavedStateLoadedPayload(
                                loadedState.get().getRound(), loadedState.get().getConsensusTimestamp()));
                return copyInitialSignedState(configuration, loadedState.get());
            }
        }
        final var stateRoot = stateRootSupplier.get();
        final var signedState = new SignedState(
                configuration, CryptoStatic::verifySignature, stateRoot, "genesis state", false, false, false);
        final var reservedSignedState = signedState.reserve("initial reservation on genesis state");
        try (reservedSignedState) {
            return copyInitialSignedState(configuration, reservedSignedState.get());
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
