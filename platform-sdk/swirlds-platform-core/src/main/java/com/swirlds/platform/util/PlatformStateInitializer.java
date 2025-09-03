// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader.SIGNED_STATE_FILE_NAME;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_CONFIG_FILE_NAME;
import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
import static com.swirlds.platform.util.BootstrapUtils.loadAppMain;
import static com.swirlds.platform.util.BootstrapUtils.setupConstructableRegistry;
import static com.swirlds.platform.util.BootstrapUtils.setupConstructableRegistryWithConfiguration;
import static com.swirlds.virtualmap.constructable.ConstructableUtils.registerVirtualMapConstructables;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.ApplicationDefinition;
import com.swirlds.platform.ApplicationDefinitionLoader;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.config.DefaultConfiguration;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.virtualmap.VirtualMap;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;

/**
 * A class initializing the platform state for further processing.
 */
public class PlatformStateInitializer {

    private static final Logger logger = LogManager.getLogger(PlatformStateInitializer.class);

    /**
     * This method satisfies all the preconditions required to load the state, loads the state
     * and passes it to further processing by the state consumer
     * @param pathToInitState a path to the initial state snapshot
     * @param configurationPaths a path to custom configurations
     * @param mainClassName an application class name
     * @param selfId self id
     * @param stateConsumer a consumer that processes the state initialized in this method
     * @throws IOException
     */
    public static void initAndProcessState(
            Path pathToInitState,
            List<Path> configurationPaths,
            String mainClassName,
            NodeId selfId,
            AppStateProcessor stateConsumer)
            throws IOException {

        Objects.requireNonNull(pathToInitState, "pathToInitState must not be null");
        Objects.requireNonNull(configurationPaths, "configurationFiles must not be null");
        Objects.requireNonNull(mainClassName, "mainClassName must not be null");
        Objects.requireNonNull(selfId, "selfId must not be null");

        final Configuration configuration;
        configuration = DefaultConfiguration.buildBasicConfiguration(
                ConfigurationBuilder.create(), FileUtils.getAbsolutePath("settings.txt"), configurationPaths);
        final PlatformContext platformContext = PlatformContext.create(configuration);

        setupConstructableRegistry();
        try {
            setupConstructableRegistryWithConfiguration(platformContext.getConfiguration());
            registerVirtualMapConstructables(platformContext.getConfiguration());
        } catch (ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }

        final PathsConfig defaultPathsConfig = ConfigurationBuilder.create()
                .withConfigDataType(PathsConfig.class)
                .build()
                .getConfigData(PathsConfig.class);

        // parameters if the app needs them
        final ApplicationDefinition appDefinition =
                ApplicationDefinitionLoader.loadDefault(defaultPathsConfig, getAbsolutePath(DEFAULT_CONFIG_FILE_NAME));
        ParameterProvider.getInstance().setParameters(appDefinition.getAppParameters());

        final SwirldMain appMain = loadAppMain(mainClassName);

        logger.info(STARTUP.getMarker(), "Loading state from {}", pathToInitState);

        final SwirldMain<?> hederaApp = createHederaApp(platformContext, appMain);

        final DeserializedSignedState deserializedSignedState = SignedStateFileReader.readStateFile(
                Path.of(pathToInitState.toString(), SIGNED_STATE_FILE_NAME),
                PlatformStateInitializer::createrNewMerkleNodeState,
                DEFAULT_PLATFORM_STATE_FACADE,
                platformContext);
        try (final ReservedSignedState initialState = deserializedSignedState.reservedSignedState()) {
            updateStateHash(hederaApp, deserializedSignedState);
            try {
                stateConsumer.initialize(initialState, hederaApp, platformContext);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static SwirldMain<?> createHederaApp(
            PlatformContext platformContext, SwirldMain<?> appMain) {
        final SwirldMain<?> hederaApp;
        Method newHederaMethod;
        try {
            newHederaMethod = appMain.getClass()
                    .getDeclaredMethod("newHedera", Metrics.class, PlatformStateFacade.class, Configuration.class);
            NoOpMetrics noOpMetrics = new NoOpMetrics();
            hederaApp = (SwirldMain<?>)
                    newHederaMethod.invoke(null, noOpMetrics, DEFAULT_PLATFORM_STATE_FACADE, platformContext.getConfiguration());
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return hederaApp;
    }

    private static MerkleNodeState createrNewMerkleNodeState(VirtualMap virtualMap) {
        try {
            Class<?> stateClass = Class.forName("com.hedera.node.app.HederaVirtualMapState");
            Constructor<?> constructor = stateClass.getConstructor(VirtualMap.class);
            return (MerkleNodeState) constructor.newInstance(virtualMap);
        } catch (ClassNotFoundException
                | NoSuchMethodException
                | InvocationTargetException
                | InstantiationException
                | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static void updateStateHash(SwirldMain<?> hederaApp, DeserializedSignedState deserializedSignedState) {
        try {
            Method setInitialStateHash = hederaApp.getClass().getDeclaredMethod("setInitialStateHash", Hash.class);
            setInitialStateHash.invoke(hederaApp, deserializedSignedState.originalHash());
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
