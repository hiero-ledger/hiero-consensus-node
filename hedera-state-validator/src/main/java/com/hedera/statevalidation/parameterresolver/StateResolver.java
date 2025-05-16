// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.parameterresolver;

import static com.hedera.statevalidation.parameterresolver.InitUtils.getConfiguration;
import static com.hedera.statevalidation.parameterresolver.InitUtils.initConfiguration;
import static com.hedera.statevalidation.parameterresolver.InitUtils.initServiceMigrator;
import static com.hedera.statevalidation.parameterresolver.InitUtils.initServiceRegistry;
import static com.swirlds.platform.state.snapshot.SignedStateFileReader.readStateFile;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.HederaStateRoot;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.node.app.version.ServicesSoftwareVersion;
import com.hedera.statevalidation.validators.Constants;
import com.swirlds.base.time.Time;
import com.swirlds.common.concurrent.ExecutorFactory;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.config.CryptoConfig;
import com.swirlds.common.crypto.engine.CryptoEngine;
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
import com.swirlds.platform.config.TransactionConfig;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.state.State;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.virtualmap.constructable.ConstructableUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

public class StateResolver implements ParameterResolver {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^((\\d+)\\.(\\d+)\\.(\\d+))\\s*(\\d+)*$");

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
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return deserializedSignedState;
    }

    public static DeserializedSignedState initState() throws IOException {
        initConfiguration();
        final ServicesRegistryImpl serviceRegistry = initServiceRegistry();
        PlatformStateFacade platformStateFacade = new PlatformStateFacade(ServicesSoftwareVersion::new);
        serviceRegistry.register(
                new RosterService(roster -> true, () -> {}, StateResolver::getState, platformStateFacade));
        final PlatformContext platformContext = createPlatformContext();
        deserializedSignedState = readStateFile(
                Path.of(Constants.STATE_DIR, "SignedState.swh").toAbsolutePath(), platformStateFacade, platformContext);
        final MerkleStateRoot servicesState = (MerkleStateRoot)
                deserializedSignedState.reservedSignedState().get().getState();

        initServiceMigrator(servicesState, platformContext.getConfiguration(), serviceRegistry);

        return deserializedSignedState;
    }

    public static State getState() {
        return (State) deserializedSignedState.reservedSignedState().get().getState();
    }

    public static SemanticVersion readVersion() {
        final String versionStr;
        try {
            InputStream resourceAsStream = StateResolver.class.getResourceAsStream("/version.txt");
            versionStr = new BufferedReader(new InputStreamReader(resourceAsStream)).readLine();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Matcher matcher = VERSION_PATTERN.matcher(versionStr);
        if (matcher.find()) {
            return new SemanticVersion(
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4)),
                    null,
                    matcher.group(5));
        }

        throw new IllegalArgumentException("Invalid version string: " + versionStr);
    }

    private static PlatformContext createPlatformContext() {
        try {
            ConstructableRegistry.getInstance().registerConstructables("com.hedera.services");
            ConstructableRegistry.getInstance().registerConstructables("com.hedera.node.app");
            ConstructableRegistry.getInstance().registerConstructables("com.hedera.hapi");
            ConstructableRegistry.getInstance().registerConstructables("com.swirlds");
            ConstructableUtils.registerVirtualMapConstructables(getConfiguration());
            BootstrapUtils.setupConstructableRegistryWithConfiguration(getConfiguration());
            final SemanticVersion servicesVersion = readVersion();

            ConstructableRegistry.getInstance()
                    .registerConstructable(new ClassConstructorPair(MerkleStateRoot.class, HederaStateRoot::new));
        } catch (ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }
        return new PlatformContext() {

            private final Configuration platformConfig = ConfigurationBuilder.create()
                    .withConfigDataType(MetricsConfig.class)
                    .withConfigDataType(TransactionConfig.class)
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

            public Cryptography getCryptography() {
                return new CryptoEngine();
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
