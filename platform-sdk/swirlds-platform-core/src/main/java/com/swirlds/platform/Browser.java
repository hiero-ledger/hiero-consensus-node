// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

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
import static com.swirlds.platform.crypto.CryptoStatic.initNodeSecurity;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.addPlatforms;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.getStateHierarchy;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.moveBrowserWindowToFront;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.setBrowserWindow;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.setStateHierarchy;
import static com.swirlds.platform.gui.internal.BrowserWindowManager.showBrowserWindow;
import static com.swirlds.platform.state.signed.StartupStateUtils.getInitialState;
import static com.swirlds.platform.system.SystemExitUtils.exitSystem;
import static com.swirlds.platform.system.address.AddressBookUtils.initializeAddressBook;
import static com.swirlds.platform.util.BootstrapUtils.getNodesToRun;
import static com.swirlds.platform.util.BootstrapUtils.loadSwirldMains;
import static com.swirlds.platform.util.BootstrapUtils.setupBrowserWindow;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SystemEnvironmentConfigSource;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.gui.GuiEventStorage;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiSource;
import com.swirlds.platform.gui.hashgraph.internal.StandardGuiSource;
import com.swirlds.platform.gui.internal.StateHierarchy;
import com.swirlds.platform.gui.internal.WinBrowser;
import com.swirlds.platform.gui.model.InfoApp;
import com.swirlds.platform.gui.model.InfoMember;
import com.swirlds.platform.gui.model.InfoSwirld;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.HashedReservedSignedState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.platform.system.SystemExitCode;
import com.swirlds.platform.system.address.AddressBookUtils;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.consensus.crypto.CryptoConstants;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.roster.RosterUtils;

/**
 * The Browser that launches the Platforms that run the apps. This is used by the demo apps to launch the
 * Platforms.
 * This class will be removed once the demo apps moved to Inversion of Control pattern to build and start platform
 * directly.
 */
@Deprecated(forRemoval = true)
public class Browser {
    // Each member is represented by an AddressBook entry in config.txt. On a given computer, a single java
    // process runs all members whose listed internal IP address matches some address on that computer. That
    // Java process will instantiate one Platform per member running on that machine. But there will be only
    // one static Browser that they all share.
    //
    // Every member, whatever computer it is running on, listens on 0.0.0.0, on its internal port. Every
    // member connects to every other member, by computing its IP address as follows: If that other member
    // is also on the same host, use 127.0.0.1. If it is on the same LAN[*], use its internal address.
    // Otherwise, use its external address.
    //
    // This way, a single config.txt can be shared across computers unchanged, even if, for example, those
    // computers are on different networks in Amazon EC2.
    //
    // [*] Two members are considered to be on the same LAN if their listed external addresses are the same.

    private static Logger logger = LogManager.getLogger(Browser.class);

    /**
     * True if the browser has been launched
     */
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    /**
     * Main method for starting the browser
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        parseCommandLineArgsAndLaunch(args);
    }

    /**
     * Parse the command line arguments and launch the browser
     *
     * @param args command line arguments
     */
    public static void parseCommandLineArgsAndLaunch(@NonNull final String... args) {
        final CommandLineArgs commandLineArgs = CommandLineArgs.parse(args);

        launch(commandLineArgs, false);
    }

    /**
     * Launch the browser with the command line arguments already parsed
     *
     * @param commandLineArgs the parsed command line arguments
     * @param pcesRecovery    if true, the platform will be started in PCES recovery mode
     */
    public static void launch(@NonNull final CommandLineArgs commandLineArgs, final boolean pcesRecovery) {
        if (STARTED.getAndSet(true)) {
            return;
        }

        initLogging();

        logger = LogManager.getLogger(Browser.class);

        try {
            launchUnhandled(commandLineArgs, pcesRecovery);
        } catch (final Throwable e) {
            logger.error(EXCEPTION.getMarker(), "Unable to start Browser", e);
            throw new RuntimeException("Unable to start Browser", e);
        }
    }

    /**
     * Launch the browser but do not handle any exceptions
     *
     * @param commandLineArgs the parsed command line arguments
     * @param pcesRecovery    if true, the platform will be started in PCES recovery mode
     */
    private static void launchUnhandled(@NonNull final CommandLineArgs commandLineArgs, final boolean pcesRecovery)
            throws Exception {
        Objects.requireNonNull(commandLineArgs);
        final ConfigurationBuilder bootstrapConfigBuilder =
                ConfigurationBuilder.create().withSource(SystemEnvironmentConfigSource.getInstance());
        BootstrapUtils.setupConfigBuilder(bootstrapConfigBuilder, getAbsolutePath(DEFAULT_SETTINGS_FILE_NAME));
        final Configuration bootstrapConfiguration = bootstrapConfigBuilder.build();

        final PathsConfig defaultPathsConfig = bootstrapConfiguration.getConfigData(PathsConfig.class);

        // Load config.txt file, parse application jar file name, main class name, address book, and parameters
        final ApplicationDefinition appDefinition =
                ApplicationDefinitionLoader.loadDefault(defaultPathsConfig, getAbsolutePath(DEFAULT_CONFIG_FILE_NAME));

        // Determine which nodes to run locally
        final AddressBook appAddressBook = appDefinition.getConfigAddressBook();
        final List<NodeId> configNodesToRun =
                bootstrapConfiguration.getConfigData(BasicConfig.class).nodesToRun();
        final Set<NodeId> cliNodesToRun = commandLineArgs.localNodesToStart();
        final Set<NodeId> validNodeIds = appAddressBook.getNodeIdSet();
        final List<NodeId> nodesToRun =
                getNodesToRun(cliNodesToRun, configNodesToRun, () -> validNodeIds, validNodeIds::contains);
        logger.info(STARTUP.getMarker(), "The following nodes {} are set to run locally", nodesToRun);

        // Load all SwirldMain instances for locally run nodes.
        final Map<NodeId, SwirldMain> appMains = loadSwirldMains(appDefinition, nodesToRun);
        ParameterProvider.getInstance().setParameters(appDefinition.getAppParameters());

        final boolean showUi = !GraphicsEnvironment.isHeadless();

        final GuiEventStorage guiEventStorage;
        final HashgraphGuiSource guiSource;
        Metrics guiMetrics = null;
        if (showUi) {
            setupBrowserWindow();
            setStateHierarchy(new StateHierarchy(null));
            final InfoApp infoApp = getStateHierarchy().getInfoApp(appDefinition.getApplicationName());
            final InfoSwirld infoSwirld = new InfoSwirld(infoApp, new byte[CryptoConstants.HASH_SIZE_BYTES]);
            new InfoMember(infoSwirld, "Node" + nodesToRun.getFirst().id());

            initNodeSecurity(appDefinition.getConfigAddressBook(), bootstrapConfiguration, Set.copyOf(nodesToRun));
            guiEventStorage = new GuiEventStorage(bootstrapConfiguration, appDefinition.getConfigAddressBook());

            guiSource = new StandardGuiSource(appDefinition.getConfigAddressBook(), guiEventStorage);
        } else {
            guiSource = null;
            guiEventStorage = null;
        }

        final Map<NodeId, SwirldsPlatform> platforms = new HashMap<>();
        for (int index = 0; index < nodesToRun.size(); index++) {
            final NodeId nodeId = nodesToRun.get(index);
            final SwirldMain appMain = appMains.get(nodeId);

            final ConfigurationBuilder configBuilder = ConfigurationBuilder.create();
            final List<Class<? extends Record>> configTypes = appMain.getConfigDataTypes();
            for (final Class<? extends Record> configType : configTypes) {
                configBuilder.withConfigDataType(configType);
            }

            rethrowIO(() -> BootstrapUtils.setupConfigBuilder(
                    configBuilder,
                    getAbsolutePath(DEFAULT_SETTINGS_FILE_NAME),
                    getAbsolutePath(DEFAULT_OVERRIDES_YAML_FILE_NAME)));
            final Configuration configuration = configBuilder.build();

            setupGlobalMetrics(configuration);
            guiMetrics = getMetricsProvider().createPlatformMetrics(nodeId);

            final RecycleBin recycleBin = RecycleBin.create(
                    guiMetrics,
                    configuration,
                    getStaticThreadManager(),
                    Time.getCurrent(),
                    FileSystemManager.create(configuration),
                    nodeId);
            final Cryptography cryptography = CryptographyProvider.getInstance();
            final KeysAndCerts keysAndCerts = initNodeSecurity(
                            appDefinition.getConfigAddressBook(), configuration, Set.copyOf(nodesToRun))
                    .get(nodeId);

            // the AddressBook is not changed after this point, so we calculate the hash now
            cryptography.digestSync(appDefinition.getConfigAddressBook());

            // Set the MerkleCryptography instance for this node
            final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(configuration);

            // Register with the ConstructableRegistry classes which need configuration.
            BootstrapUtils.setupConstructableRegistryWithConfiguration(configuration);

            // Create platform context
            final PlatformContext platformContext = PlatformContext.create(
                    configuration,
                    Time.getCurrent(),
                    guiMetrics,
                    FileSystemManager.create(configuration),
                    recycleBin,
                    merkleCryptography);
            // Each platform needs a different temporary state on disk.
            MerkleDb.resetDefaultInstancePath();
            PlatformStateFacade platformStateFacade = new PlatformStateFacade();
            // Create the initial state for the platform
            ConsensusStateEventHandler consensusStateEventHandler = appMain.newConsensusStateEvenHandler();
            final HashedReservedSignedState reservedState = getInitialState(
                    recycleBin,
                    appMain.getSemanticVersion(),
                    appMain::newStateRoot,
                    appMain.getClass().getName(),
                    appDefinition.getSwirldName(),
                    nodeId,
                    appDefinition.getConfigAddressBook(),
                    platformStateFacade,
                    platformContext);
            final ReservedSignedState initialState = reservedState.state();

            // Initialize the address book
            final AddressBook addressBook = initializeAddressBook(
                    nodeId,
                    appMain.getSemanticVersion(),
                    initialState,
                    appDefinition.getConfigAddressBook(),
                    platformContext,
                    consensusStateEventHandler,
                    platformStateFacade);

            // Build the platform with the given values
            final State state = initialState.get().getState();
            final long round = platformStateFacade.roundOf(state);
            final PlatformBuilder builder = PlatformBuilder.create(
                    appMain.getClass().getName(),
                    appDefinition.getSwirldName(),
                    appMain.getSemanticVersion(),
                    initialState,
                    consensusStateEventHandler,
                    nodeId,
                    AddressBookUtils.formatConsensusEventStreamName(addressBook, nodeId),
                    RosterUtils.buildRosterHistory(state, round),
                    platformStateFacade);
            if (showUi && index == 0) {
                builder.withPreconsensusEventCallback(guiEventStorage::handlePreconsensusEvent);
                builder.withConsensusSnapshotOverrideCallback(guiEventStorage::handleSnapshotOverride);
            }
            builder.withSystemTransactionEncoderCallback(appMain::encodeSystemTransaction);

            // Build platform using the Inversion of Control pattern by injecting all needed
            // dependencies into the PlatformBuilder.
            final SwirldsPlatform platform = (SwirldsPlatform) builder.withConfiguration(configuration)
                    .withPlatformContext(platformContext)
                    .withKeysAndCerts(keysAndCerts)
                    .build();
            platforms.put(nodeId, platform);

            if (showUi) {
                if (index == 0) {
                    guiMetrics = platform.getContext().getMetrics();
                }
            }
        }

        addPlatforms(platforms.values());

        // FUTURE WORK: PCES recovery not compatible with non-Browser launched apps
        if (pcesRecovery) {
            // PCES recovery is only expected to be done on a single node
            // due to the structure of Browser atm, it makes more sense to enable the feature for multiple platforms
            platforms.values().forEach(SwirldsPlatform::performPcesRecovery);
            exitSystem(SystemExitCode.NO_ERROR, "PCES recovery done");
        }

        startPlatforms(new ArrayList<>(platforms.values()), appMains);

        if (showUi) {
            setBrowserWindow(
                    new WinBrowser(nodesToRun.getFirst(), guiSource, guiEventStorage.getConsensus(), guiMetrics));
            showBrowserWindow(null);
            moveBrowserWindowToFront();
        }
    }

    /**
     * Start all local platforms.
     *
     * @param platforms the platforms to start
     */
    private static void startPlatforms(
            @NonNull final List<SwirldsPlatform> platforms, @NonNull final Map<NodeId, SwirldMain> appMains) {

        final List<Thread> startThreads = new ArrayList<>();
        for (final SwirldsPlatform platform : platforms) {
            final Thread thread = new ThreadConfiguration(getStaticThreadManager())
                    .setThreadName("start-node-" + platform.getSelfId().id())
                    .setRunnable(() -> startPlatform(platform, appMains.get(platform.getSelfId())))
                    .build(true);
            startThreads.add(thread);
        }

        for (final Thread startThread : startThreads) {
            try {
                startThread.join();
            } catch (final InterruptedException e) {
                logger.error(EXCEPTION.getMarker(), "Interrupted while waiting for platform to start", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Start a platform and its associated app.
     *
     * @param platform the platform to start
     * @param appMain  the app to start
     */
    private static void startPlatform(@NonNull final SwirldsPlatform platform, @NonNull final SwirldMain appMain) {
        appMain.init(platform, platform.getSelfId());
        platform.start();
        new ThreadConfiguration(getStaticThreadManager())
                .setNodeId(platform.getSelfId())
                .setComponent("app")
                .setThreadName("appMain")
                .setRunnable(appMain)
                .setDaemon(false)
                .build(true);
    }
}
