// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.parameterresolver;

import static com.hedera.statevalidation.parameterresolver.InitUtils.getConfiguration;
import static com.hedera.statevalidation.parameterresolver.InitUtils.initConfiguration;
import static com.hedera.statevalidation.parameterresolver.InitUtils.initServiceMigrator;
import static com.hedera.statevalidation.parameterresolver.InitUtils.initServiceRegistry;
import static com.swirlds.platform.state.snapshot.SignedStateFileReader.readStateFile;

import com.hedera.node.app.HederaVirtualMapState;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.statevalidation.validators.Constants;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.NoOpRecycleBin;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.state.State;
import com.swirlds.virtualmap.constructable.ConstructableUtils;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.config.CryptoConfig;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

public class StateResolver implements ParameterResolver {

    public static PlatformContext PLATFORM_CONTEXT;
    static DeserializedSignedState deserializedSignedState;

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == DeserializedSignedState.class;
    }

    @Override
    public DeserializedSignedState resolveParameter(
            ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {

        if (deserializedSignedState == null) {
            try {
                initState();
            } catch (ConstructableRegistryException | IOException e) {
                throw new RuntimeException(e);
            }
        }

        return deserializedSignedState;
    }

    public static DeserializedSignedState initState() throws ConstructableRegistryException, IOException {
        initConfiguration();
        PLATFORM_CONTEXT = createPlatformContext();
        final ServicesRegistryImpl serviceRegistry = initServiceRegistry();
        PlatformStateFacade platformStateFacade = PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
        serviceRegistry.register(
                new RosterService(roster -> true, (r, b) -> {}, StateResolver::getState, platformStateFacade));
        deserializedSignedState = readStateFile(
                Path.of(Constants.STATE_DIR, "SignedState.swh").toAbsolutePath(),
                HederaVirtualMapState::new,
                platformStateFacade,
                PLATFORM_CONTEXT);

        initServiceMigrator(getState(), PLATFORM_CONTEXT, serviceRegistry);

        return deserializedSignedState;
    }

    public static State getState() {
        return deserializedSignedState.reservedSignedState().get().getState();
    }

    private static PlatformContext createPlatformContext() throws ConstructableRegistryException {
        ConstructableRegistry.getInstance().registerConstructables("com.hedera.services");
        ConstructableRegistry.getInstance().registerConstructables("com.hedera.node.app");
        ConstructableRegistry.getInstance().registerConstructables("com.hedera.hapi");
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");
        ConstructableRegistry.getInstance().registerConstructables("org.hiero.base");

        ConstructableUtils.registerVirtualMapConstructables(getConfiguration());
        BootstrapUtils.setupConstructableRegistryWithConfiguration(getConfiguration());

        return new PlatformContext() {

            private final Configuration platformConfig = ConfigurationBuilder.create()
                    .withConfigDataType(MetricsConfig.class)
                    .withConfigDataType(CryptoConfig.class)
                    .withConfigDataType(BasicConfig.class)
                    .build();

            @Override
            public MerkleCryptography getMerkleCryptography() {
                return MerkleCryptographyFactory.create(platformConfig);
            }

            public Configuration getConfiguration() {
                return InitUtils.getConfiguration();
            }

            public Metrics getMetrics() {
                return new NoOpMetrics();
            }

            public Time getTime() {
                return Time.getCurrent();
            }

            @Override
            public ExecutorFactory getExecutorFactory() {
                return null;
            }

            @Override
            public RecycleBin getRecycleBin() {
                return new NoOpRecycleBin();
            }

            @Override
            public FileSystemManager getFileSystemManager() {
                return FileSystemManager.create(getConfiguration());
            }
        };
    }
}
