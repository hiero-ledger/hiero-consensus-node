// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.system.SystemExitCode.NODE_ADDRESS_MISMATCH;
import static com.swirlds.platform.system.SystemExitUtils.exitSystem;
import static com.swirlds.virtualmap.constructable.ConstructableUtils.registerVirtualMapConstructables;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.utility.CommonUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.config.extensions.export.ConfigExport;
import com.swirlds.config.extensions.sources.LegacyFileConfigSource;
import com.swirlds.config.extensions.sources.YamlConfigSource;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.platform.ApplicationDefinition;
import com.swirlds.platform.JVMPauseDetectorThread;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.config.internal.ConfigMappings;
import com.swirlds.platform.config.internal.PlatformConfigUtils;
import com.swirlds.platform.config.legacy.ConfigurationException;
import com.swirlds.platform.gui.WindowConfig;
import com.swirlds.platform.health.OSHealthCheckConfig;
import com.swirlds.platform.health.OSHealthChecker;
import com.swirlds.platform.health.clock.OSClockSpeedSourceChecker;
import com.swirlds.platform.health.entropy.OSEntropyChecker;
import com.swirlds.platform.health.filesystem.OSFileSystemChecker;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.swirldapp.AppLoaderException;
import com.swirlds.platform.swirldapp.SwirldAppLoader;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.HapiUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.awt.Dimension;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JFrame;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.StackTrace;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.Address;

/**
 * Utility methods that are helpful when starting up a JVM.
 */
public final class BootstrapUtils {

    /**
     * The logger for this class
     */
    private static final Logger logger = LogManager.getLogger(BootstrapUtils.class);

    private BootstrapUtils() {}

    /**
     * Load the configuration for the platform without overrides.
     *
     * @param configurationBuilder the configuration builder to setup
     * @param settingsPath         the path to the settings.txt file
     * @throws IOException if there is a problem reading the configuration files
     */
    public static void setupConfigBuilder(
            @NonNull final ConfigurationBuilder configurationBuilder, @NonNull final Path settingsPath)
            throws IOException {
        setupConfigBuilder(configurationBuilder, settingsPath, null);
    }

    /**
     * Load the configuration for the platform.
     *
     * @param configurationBuilder the configuration builder to setup
     * @param settingsPath         the path to the settings.txt file
     * @param nodeOverridesPath    the path to the node-overrides.yaml file
     * @throws IOException if there is a problem reading the configuration files
     */
    public static void setupConfigBuilder(
            @NonNull final ConfigurationBuilder configurationBuilder,
            @NonNull final Path settingsPath,
            @Nullable final Path nodeOverridesPath)
            throws IOException {

        final ConfigSource settingsConfigSource = LegacyFileConfigSource.ofSettingsFile(settingsPath);
        final ConfigSource mappedSettingsConfigSource = ConfigMappings.addConfigMapping(settingsConfigSource);
        configurationBuilder.autoDiscoverExtensions().withSource(mappedSettingsConfigSource);

        if (nodeOverridesPath != null) {
            final ConfigSource yamlConfigSource = new YamlConfigSource(nodeOverridesPath);
            configurationBuilder.withSource(yamlConfigSource);
        }
    }

    /**
     * Perform health all health checks
     *
     * @param configPath    the path to the config.txt file
     * @param configuration the configuration
     */
    public static void performHealthChecks(@NonNull final Path configPath, @NonNull final Configuration configuration) {
        requireNonNull(configuration);
        final OSFileSystemChecker osFileSystemChecker = new OSFileSystemChecker(configPath);

        OSHealthChecker.performOSHealthChecks(
                configuration.getConfigData(OSHealthCheckConfig.class),
                List.of(
                        OSClockSpeedSourceChecker::performClockSourceSpeedCheck,
                        OSEntropyChecker::performEntropyChecks,
                        osFileSystemChecker::performFileSystemCheck));
    }

    /**
     * Sets up the browser window
     */
    public static void setupBrowserWindow()
            throws UnsupportedLookAndFeelException, ClassNotFoundException, InstantiationException,
                    IllegalAccessException {
        // discover the inset size and set the look and feel
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        final JFrame jframe = new JFrame();
        jframe.setPreferredSize(new Dimension(200, 200));
        jframe.pack();
        WindowConfig.setInsets(jframe.getInsets());
        jframe.dispose();
    }

    /**
     * Add all classes to the constructable registry.
     */
    public static void setupConstructableRegistry() {
        try {
            ConstructableRegistry.getInstance().registerConstructables("");
        } catch (final ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Add all classes to the constructable registry which need the configuration.
     */
    public static void setupConstructableRegistryWithConfiguration(final Configuration configuration) {
        try {
            ConstructableRegistry.getInstance()
                    .registerConstructable(new ClassConstructorPair(
                            MerkleDbDataSourceBuilder.class, () -> new MerkleDbDataSourceBuilder(configuration)));
            registerVirtualMapConstructables(configuration);
        } catch (final ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Load the SwirldMain for the app.
     *
     * @param appMainName the name of the app main class
     */
    public static @NonNull SwirldMain loadAppMain(@NonNull final String appMainName) {
        requireNonNull(appMainName);
        try {
            final Class<?> mainClass = Class.forName(appMainName);
            final Constructor<?>[] constructors = mainClass.getDeclaredConstructors();
            Constructor<?> constructor = null;
            for (final Constructor<?> c : constructors) {
                if (c.getGenericParameterTypes().length == 0) {
                    constructor = c;
                    break;
                }
            }

            if (constructor == null) {
                throw new RuntimeException("Class " + appMainName + " does not have a zero arg constructor.");
            }

            return (SwirldMain) constructor.newInstance();
        } catch (final ClassNotFoundException
                | InstantiationException
                | IllegalAccessException
                | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Detect if there is a software upgrade. This method will throw an IllegalStateException if the loaded signed state
     * software version is greater than the current application software version.
     *
     * @param appVersion        the version of the app
     * @param loadedSignedState the signed state that was loaded from disk
     * @return true if there is a software upgrade, false otherwise
     */
    public static boolean detectSoftwareUpgrade(
            @NonNull final SemanticVersion appVersion,
            @Nullable final SignedState loadedSignedState,
            @NonNull final PlatformStateFacade platformStateFacade) {
        requireNonNull(appVersion, "The app version must not be null.");

        final SemanticVersion loadedSoftwareVersion;
        if (loadedSignedState == null) {
            loadedSoftwareVersion = null;
        } else {
            final State state = loadedSignedState.getState();
            loadedSoftwareVersion = platformStateFacade.creationSoftwareVersionOf(state);
        }
        final int versionComparison = loadedSoftwareVersion == null
                ? 1
                : HapiUtils.SEMANTIC_VERSION_COMPARATOR.compare(appVersion, loadedSoftwareVersion);
        final boolean softwareUpgrade;
        if (versionComparison < 0) {
            throw new IllegalStateException(
                    "The current software version `" + appVersion + "` is prior to the software version `"
                            + loadedSoftwareVersion + "` that created the state that was loaded from disk.");
        } else if (versionComparison > 0) {
            softwareUpgrade = true;
            logger.info(
                    STARTUP.getMarker(),
                    "Software upgrade in progress. Previous software version was {}, current version is {}.",
                    loadedSoftwareVersion,
                    appVersion);
        } else {
            softwareUpgrade = false;
            logger.info(STARTUP.getMarker(), "Not upgrading software, current software is version {}.", appVersion);
        }
        return softwareUpgrade;
    }

    /**
     * Instantiate and start the JVMPauseDetectorThread, if enabled via the
     * {@link BasicConfig#jvmPauseDetectorSleepMs()} setting.
     *
     * @param configuration the configuration object
     */
    public static void startJVMPauseDetectorThread(@NonNull final Configuration configuration) {
        requireNonNull(configuration);

        final BasicConfig basicConfig = configuration.getConfigData(BasicConfig.class);
        if (basicConfig.jvmPauseDetectorSleepMs() > 0) {
            final JVMPauseDetectorThread jvmPauseDetectorThread = new JVMPauseDetectorThread(
                    (pauseTimeMs, allocTimeMs) -> {
                        if (pauseTimeMs > basicConfig.jvmPauseReportMs()) {
                            logger.warn(
                                    EXCEPTION.getMarker(),
                                    "jvmPauseDetectorThread detected JVM paused for {} ms, allocation pause {} ms",
                                    pauseTimeMs,
                                    allocTimeMs);
                        }
                    },
                    basicConfig.jvmPauseDetectorSleepMs());
            jvmPauseDetectorThread.start();
            logger.debug(STARTUP.getMarker(), "jvmPauseDetectorThread started");
        }
    }

    /**
     * Build the app main.
     *
     * @param appDefinition the app definition
     * @param appLoader     an object capable of loading the app
     * @return the new app main
     */
    public static @NonNull SwirldMain buildAppMain(
            @NonNull final ApplicationDefinition appDefinition, @NonNull final SwirldAppLoader appLoader) {
        requireNonNull(appDefinition);
        requireNonNull(appLoader);
        try {
            return appLoader.instantiateSwirldMain();
        } catch (final Exception e) {
            CommonUtils.tellUserConsolePopup(
                    "ERROR",
                    "ERROR: There are problems starting class " + appDefinition.getMainClassName() + "\n"
                            + StackTrace.getStackTrace(e));
            logger.error(EXCEPTION.getMarker(), "Problems with class {}", appDefinition.getMainClassName(), e);
            throw new RuntimeException("Problems with class " + appDefinition.getMainClassName(), e);
        }
    }

    /**
     * Writes all settings and config values to settingsUsed.txt
     *
     * @param configuration the configuration values to write
     */
    public static void writeSettingsUsed(@NonNull final Configuration configuration) {
        requireNonNull(configuration);
        final StringBuilder settingsUsedBuilder = new StringBuilder();

        // Add all settings values to the string builder
        final PathsConfig pathsConfig = configuration.getConfigData(PathsConfig.class);

        settingsUsedBuilder.append(System.lineSeparator());
        settingsUsedBuilder.append("------------- All Configuration -------------");
        settingsUsedBuilder.append(System.lineSeparator());

        // Add all config values to the string builder
        ConfigExport.addConfigContents(configuration, settingsUsedBuilder);

        // Write the settingsUsed.txt file
        final Path settingsUsedPath =
                pathsConfig.getSettingsUsedDir().resolve(PlatformConfigUtils.SETTING_USED_FILENAME);
        try (final OutputStream outputStream = new FileOutputStream(settingsUsedPath.toFile())) {
            outputStream.write(settingsUsedBuilder.toString().getBytes(StandardCharsets.UTF_8));
        } catch (final IOException | RuntimeException e) {
            logger.error(EXCEPTION.getMarker(), "Failed to write settingsUsed to file {}", settingsUsedPath, e);
        }
    }

    /**
     * Determine which nodes should be run locally. The nodes specified on the commandline override the nodes specified
     * through the system environment.   If no nodes are specified on the commandline or through the system environment,
     * all nodes in the address book are returned to run locally.
     *
     * @param cliNodesToRun nodes specified to start by the user on the command line
     * @param configNodesToRun nodes specified to start by the user in configuration
     * @param knownNodeIds the set of known node ids
     * @param validNodeId a predicate that determines if a node id is valid
     * @return A non-empty list of nodes to run locally
     * @throws IllegalArgumentException if a node to run is invalid or the list of nodes to run is empty
     */
    public static @NonNull List<NodeId> getNodesToRun(
            @NonNull final Set<NodeId> cliNodesToRun,
            @NonNull final List<NodeId> configNodesToRun,
            @NonNull final Supplier<Set<NodeId>> knownNodeIds,
            @NonNull final Predicate<NodeId> validNodeId) {
        requireNonNull(validNodeId);
        requireNonNull(cliNodesToRun);
        requireNonNull(configNodesToRun);
        requireNonNull(knownNodeIds);

        final List<NodeId> nodesToRun = new ArrayList<>();
        if (cliNodesToRun.isEmpty()) {
            if (configNodesToRun.isEmpty()) {
                // If no node ids are provided by cli or config, run all nodes from the address book;
                // this will only be a useful fallback for Browser-based platform testing apps
                return new ArrayList<>(knownNodeIds.get());
            } else {
                // CLI did not provide any nodes to run, so use the node ids from the config
                nodesToRun.addAll(configNodesToRun);
            }
        } else {
            // CLI provided nodes override environment provided nodes to run
            nodesToRun.addAll(cliNodesToRun);
        }

        for (final NodeId nodeId : nodesToRun) {
            if (!validNodeId.test(nodeId)) {
                final String errorMessage = "Node " + nodeId + " is invalid and cannot be started.";
                // all nodes to start must exist in the address book
                logger.error(EXCEPTION.getMarker(), errorMessage);
                exitSystem(NODE_ADDRESS_MISMATCH, errorMessage);
                // the following throw is not reachable in production,
                // but reachable in testing with static mocked system exit calls.
                throw new ConfigurationException(errorMessage);
            }
        }

        return nodesToRun;
    }

    /**
     * Load all {@link SwirldMain} instances for locally run nodes.  Locally run nodes are indicated in two possible
     * ways.  One is through the set of local nodes to start.  The other is through {@link Address ::isOwnHost} being
     * true.
     *
     * @param appDefinition the application definition
     * @param nodesToRun    the locally run nodeIds
     * @return a map from nodeIds to {@link SwirldMain} instances
     */
    @NonNull
    public static Map<NodeId, SwirldMain> loadSwirldMains(
            @NonNull final ApplicationDefinition appDefinition, @NonNull final Collection<NodeId> nodesToRun) {
        requireNonNull(appDefinition, "appDefinition must not be null");
        requireNonNull(nodesToRun, "nodesToRun must not be null");
        try {
            // Create the SwirldAppLoader
            final SwirldAppLoader appLoader;
            try {
                appLoader =
                        SwirldAppLoader.loadSwirldApp(appDefinition.getMainClassName(), appDefinition.getAppJarPath());
            } catch (final AppLoaderException e) {
                CommonUtils.tellUserConsolePopup("ERROR", e.getMessage());
                throw e;
            }

            // Register all RuntimeConstructable classes
            logger.debug(STARTUP.getMarker(), "Scanning the classpath for RuntimeConstructable classes");
            final long start = System.currentTimeMillis();
            ConstructableRegistry.getInstance().registerConstructables("", appLoader.getClassLoader());
            logger.debug(
                    STARTUP.getMarker(),
                    "Done with registerConstructables, time taken {}ms",
                    System.currentTimeMillis() - start);

            // Create the SwirldMain instances
            final Map<NodeId, SwirldMain> appMains = new HashMap<>();
            for (final NodeId nodeId : nodesToRun) {
                appMains.put(nodeId, buildAppMain(appDefinition, appLoader));
            }
            return appMains;
        } catch (final Exception ex) {
            throw new RuntimeException("Error loading SwirldMains", ex);
        }
    }
}
