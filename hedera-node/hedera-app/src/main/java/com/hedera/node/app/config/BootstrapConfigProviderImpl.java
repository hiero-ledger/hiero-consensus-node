// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.config;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.converter.BytesConverter;
import com.hedera.node.config.converter.CongestionMultipliersConverter;
import com.hedera.node.config.converter.EntityScaleFactorsConverter;
import com.hedera.node.config.converter.LongPairConverter;
import com.hedera.node.config.converter.PermissionedAccountsRangeConverter;
import com.hedera.node.config.converter.SemanticVersionConverter;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.GovernanceTransactionsConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.OpsDurationConfig;
import com.hedera.node.config.data.QuiescenceConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.sources.PropertyConfigSource;
import com.hedera.node.config.types.CongestionMultipliers;
import com.hedera.node.config.types.EntityScaleFactors;
import com.hedera.node.config.types.LongPair;
import com.hedera.node.config.types.PermissionedAccountsRange;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SystemEnvironmentConfigSource;
import com.swirlds.config.extensions.sources.SystemPropertiesConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Constructs and returns a {@link Configuration} instance that contains only those configs used at startup during
 * the bootstrapping phase.
 */
public class BootstrapConfigProviderImpl extends ConfigProviderBase {
    private static final String SERVICES_VERSION_OVERRIDE_PROPERTY = "hapi.spec.override.services.version";
    private static final String HAPI_PROTO_VERSION_OVERRIDE_PROPERTY = "hapi.spec.override.hapi.proto.version";
    private static final String HEDERA_SERVICES_VERSION_KEY = "hedera.services.version";
    private static final String HAPI_PROTO_VERSION_KEY = "hapi.proto.version";
    private static final int SEMANTIC_VERSION_OVERRIDE_ORDINAL = 600;

    /** The bootstrap configuration. */
    private final Configuration bootstrapConfig;

    public BootstrapConfigProviderImpl() {
        this(null);
    }

    /**
     * Create a new instance.
     *
     * <p>Uses the default path for the semantic-version.properties file to get the version information. It uses the
     * default path for the application.properties file, or the path specified by the environment variable
     * ({@link ConfigProviderBase#APPLICATION_PROPERTIES_PATH_ENV}), to get other properties. None of these properties
     * used at bootstrap are those stored in the ledger state.
     */
    public BootstrapConfigProviderImpl(@Nullable final Map<String, String> overrideValues) {
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
                .withConfigDataType(QuiescenceConfig.class)
                .withConfigDataType(ContractsConfig.class)
                .withConfigDataType(BlockNodeConnectionConfig.class)
                .withConfigDataType(OpsDurationConfig.class)
                .withConfigDataType(JumboTransactionsConfig.class)
                .withConfigDataType(FeesConfig.class)
                .withConfigDataType(GovernanceTransactionsConfig.class)
                .withConverter(Bytes.class, new BytesConverter())
                .withConverter(SemanticVersion.class, new SemanticVersionConverter())
                .withConverter(CongestionMultipliers.class, new CongestionMultipliersConverter())
                .withConverter(EntityScaleFactors.class, new EntityScaleFactorsConverter())
                .withConverter(PermissionedAccountsRange.class, new PermissionedAccountsRangeConverter())
                .withConverter(LongPair.class, new LongPairConverter());
        applySemanticVersionOverrides(builder);

        try {
            addFileSource(builder, APPLICATION_PROPERTIES_PATH_ENV, APPLICATION_PROPERTIES_DEFAULT_PATH, 100);
        } catch (final Exception e) {
            throw new IllegalStateException("Can not create config source for application properties", e);
        }
        if (overrideValues != null) {
            overrideValues.forEach(builder::withValue);
        }
        this.bootstrapConfig = builder.build();
    }

    private static void applySemanticVersionOverrides(@NonNull final ConfigurationBuilder builder) {
        final var servicesOverride = System.getProperty(SERVICES_VERSION_OVERRIDE_PROPERTY);
        if (servicesOverride == null || servicesOverride.isBlank()) {
            return;
        }
        final var hapiOverride = System.getProperty(HAPI_PROTO_VERSION_OVERRIDE_PROPERTY, servicesOverride);
        final var props = new HashMap<String, String>();
        props.put(HEDERA_SERVICES_VERSION_KEY, servicesOverride);
        props.put(HAPI_PROTO_VERSION_KEY, hapiOverride);
        builder.withSource(new PropertyConfigSource(propsAsProperties(props), SEMANTIC_VERSION_OVERRIDE_ORDINAL));
    }

    private static java.util.Properties propsAsProperties(@NonNull final Map<String, String> entries) {
        final var properties = new java.util.Properties();
        entries.forEach(properties::setProperty);
        return properties;
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
