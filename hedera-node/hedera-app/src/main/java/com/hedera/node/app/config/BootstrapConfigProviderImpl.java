// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.config;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.converter.BytesConverter;
import com.hedera.node.config.converter.LongPairConverter;
import com.hedera.node.config.converter.SemanticVersionConverter;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.OpsDurationConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.sources.PropertyConfigSource;
import com.hedera.node.config.types.LongPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SystemEnvironmentConfigSource;
import com.swirlds.config.extensions.sources.SystemPropertiesConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Constructs and returns a {@link Configuration} instance that contains only those configs used at startup during
 * the bootstrapping phase.
 */
public class BootstrapConfigProviderImpl extends ConfigProviderBase {
    /** The bootstrap configuration. */
    private final Configuration bootstrapConfig;

    /**
     * Create a new instance.
     *
     * <p>Uses the default path for the semantic-version.properties file to get the version information. It uses the
     * default path for the application.properties file, or the path specified by the environment variable
     * ({@link ConfigProviderBase#APPLICATION_PROPERTIES_PATH_ENV}), to get other properties. None of these properties
     * used at bootstrap are those stored in the ledger state.
     */
    public BootstrapConfigProviderImpl() {
        final var builder = ConfigurationBuilder.create()
                .withSource(SystemEnvironmentConfigSource.getInstance())
                .withSource(SystemPropertiesConfigSource.getInstance())
                .withSource(new PropertyConfigSource(SEMANTIC_VERSION_PROPERTIES_DEFAULT_PATH, 500))
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BootstrapConfig.class)
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(FilesConfig.class)
                .withConfigDataType(VersionConfig.class)
                .withConfigDataType(LedgerConfig.class)
                .withConfigDataType(AccountsConfig.class)
                .withConfigDataType(TssConfig.class)
                .withConfigDataType(ContractsConfig.class)
                .withConfigDataType(BlockNodeConnectionConfig.class)
                .withConfigDataType(OpsDurationConfig.class)
                .withConverter(Bytes.class, new BytesConverter())
                .withConverter(SemanticVersion.class, new SemanticVersionConverter())
                .withConverter(LongPair.class, new LongPairConverter());

        try {
            addFileSource(builder, APPLICATION_PROPERTIES_PATH_ENV, APPLICATION_PROPERTIES_DEFAULT_PATH, 100);
        } catch (final Exception e) {
            throw new IllegalStateException("Can not create config source for application properties", e);
        }

        this.bootstrapConfig = builder.build();
    }

    /**
     * Gets the bootstrap configuration.
     *
     * @return The configuration to use during bootstrap
     */
    @NonNull
    public Configuration configuration() {
        return bootstrapConfig;
    }

    @NonNull
    @Override
    public VersionedConfiguration getConfiguration() {
        return new VersionedConfigImpl(bootstrapConfig, 0);
    }
}
