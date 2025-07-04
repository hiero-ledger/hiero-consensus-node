// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.testfixtures;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.hapi.utils.sysfiles.domain.KnownBlockValues;
import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.converter.AccountIDConverter;
import com.hedera.node.config.converter.BytesConverter;
import com.hedera.node.config.converter.CongestionMultipliersConverter;
import com.hedera.node.config.converter.ContractIDConverter;
import com.hedera.node.config.converter.EntityScaleFactorsConverter;
import com.hedera.node.config.converter.FileIDConverter;
import com.hedera.node.config.converter.FunctionalitySetConverter;
import com.hedera.node.config.converter.KeyValuePairConverter;
import com.hedera.node.config.converter.KnownBlockValuesConverter;
import com.hedera.node.config.converter.LongPairConverter;
import com.hedera.node.config.converter.PermissionedAccountsRangeConverter;
import com.hedera.node.config.converter.ScaleFactorConverter;
import com.hedera.node.config.converter.SemanticVersionConverter;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ApiPermissionConfig;
import com.hedera.node.config.data.AtomicBatchConfig;
import com.hedera.node.config.data.AutoRenew2Config;
import com.hedera.node.config.data.AutoRenewConfig;
import com.hedera.node.config.data.BalancesConfig;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.CacheConfig;
import com.hedera.node.config.data.ConsensusConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.ExpiryConfig;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.node.config.data.GrpcUsageTrackerConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.NettyConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.data.OpsDurationConfig;
import com.hedera.node.config.data.RatesConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.data.StatsConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.data.TopicsConfig;
import com.hedera.node.config.data.TraceabilityConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.types.CongestionMultipliers;
import com.hedera.node.config.types.EntityScaleFactors;
import com.hedera.node.config.types.HederaFunctionalitySet;
import com.hedera.node.config.types.KeyValuePair;
import com.hedera.node.config.types.LongPair;
import com.hedera.node.config.types.PermissionedAccountsRange;
import com.hedera.node.config.validation.EmulatesMapValidator;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.config.BasicCommonConfig;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.FileSystemManagerConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.prometheus.PrometheusConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.config.AddressBookConfig;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.health.OSHealthCheckConfig;
import com.swirlds.platform.network.SocketConfig;
import com.swirlds.platform.system.status.PlatformStatusConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.config.CryptoConfig;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.config.TransactionConfig;

/**
 * A builder for creating {@link TestConfigBuilder} instances, or {@link Configuration} instances for testing. The
 * builder is preloaded with all known configuration types, validators, and converters, but provides a convenient
 * way to add configuration sources inline in the test that override the default config settings.
 */
public final class HederaTestConfigBuilder {

    private HederaTestConfigBuilder() {}

    /**
     * Creates a new {@link TestConfigBuilder} instance. Attempts to register all config records that are part of the
     * base packages {@code com.hedera} or {@code com.swirlds} are automatically registered. If false, no config record
     * is registered.
     *
     * @return the new {@link TestConfigBuilder} instance
     */
    @NonNull
    public static TestConfigBuilder create() {
        return new TestConfigBuilder(false)
                // Configuration Data Types from the Hashgraph Platform.
                .withConfigDataType(BasicConfig.class)
                .withConfigDataType(BasicCommonConfig.class)
                .withConfigDataType(ConsensusConfig.class)
                .withConfigDataType(EventConfig.class)
                .withConfigDataType(OSHealthCheckConfig.class)
                .withConfigDataType(PathsConfig.class)
                .withConfigDataType(SocketConfig.class)
                .withConfigDataType(StateConfig.class)
                .withConfigDataType(StateCommonConfig.class)
                .withConfigDataType(TransactionConfig.class)
                .withConfigDataType(CryptoConfig.class)
                .withConfigDataType(TemporaryFileConfig.class)
                .withConfigDataType(FileSystemManagerConfig.class)
                .withConfigDataType(ReconnectConfig.class)
                .withConfigDataType(MetricsConfig.class)
                .withConfigDataType(PrometheusConfig.class)
                .withConfigDataType(PlatformStatusConfig.class)
                .withConfigDataType(MerkleDbConfig.class)
                .withConfigDataType(AddressBookConfig.class)
                .withConfigDataType(OpsDurationConfig.class)
                /*
                These data types from the platform were not available on the classpath. Add if needed later.
                .withConfigDataType(ThreadConfig.class)
                .withConfigDataType(DispatchConfiguration.class)
                .withConfigDataType(PreconsensusEventStreamConfig.class)
                .withConfigDataType(EventCreationConfig.class)
                .withConfigDataType(ChatterConfig.class)
                .withConfigDataType(SyncConfig.class)
                .withConfigDataType(UptimeConfig.class)
                 */
                .withConfigDataType(VirtualMapConfig.class)

                // These data types, converters, and validators are defined by services.
                .withConfigDataType(AccountsConfig.class)
                .withConfigDataType(ApiPermissionConfig.class)
                .withConfigDataType(AutoRenew2Config.class)
                .withConfigDataType(AutoRenewConfig.class)
                .withConfigDataType(BalancesConfig.class)
                .withConfigDataType(BlockRecordStreamConfig.class)
                .withConfigDataType(BootstrapConfig.class)
                .withConfigDataType(CacheConfig.class)
                .withConfigDataType(ConsensusConfig.class)
                .withConfigDataType(ContractsConfig.class)
                .withConfigDataType(EntitiesConfig.class)
                .withConfigDataType(ExpiryConfig.class)
                .withConfigDataType(FeesConfig.class)
                .withConfigDataType(FilesConfig.class)
                .withConfigDataType(GrpcConfig.class)
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(LedgerConfig.class)
                .withConfigDataType(NettyConfig.class)
                .withConfigDataType(NetworkAdminConfig.class)
                .withConfigDataType(RatesConfig.class)
                .withConfigDataType(SchedulingConfig.class)
                .withConfigDataType(StakingConfig.class)
                .withConfigDataType(StatsConfig.class)
                .withConfigDataType(TokensConfig.class)
                .withConfigDataType(TopicsConfig.class)
                .withConfigDataType(TraceabilityConfig.class)
                .withConfigDataType(VersionConfig.class)
                .withConfigDataType(NodesConfig.class)
                .withConfigDataType(TssConfig.class)
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockNodeConnectionConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withConfigDataType(AtomicBatchConfig.class)
                .withConfigDataType(JumboTransactionsConfig.class)
                .withConfigDataType(GrpcUsageTrackerConfig.class)
                .withConverter(CongestionMultipliers.class, new CongestionMultipliersConverter())
                .withConverter(EntityScaleFactors.class, new EntityScaleFactorsConverter())
                .withConverter(KnownBlockValues.class, new KnownBlockValuesConverter())
                .withConverter(ScaleFactor.class, new ScaleFactorConverter())
                .withConverter(AccountID.class, new AccountIDConverter())
                .withConverter(ContractID.class, new ContractIDConverter())
                .withConverter(FileID.class, new FileIDConverter())
                .withConverter(PermissionedAccountsRange.class, new PermissionedAccountsRangeConverter())
                .withConverter(SemanticVersion.class, new SemanticVersionConverter())
                .withConverter(KeyValuePair.class, new KeyValuePairConverter())
                .withConverter(LongPair.class, new LongPairConverter())
                .withConverter(HederaFunctionalitySet.class, new FunctionalitySetConverter())
                .withConverter(Bytes.class, new BytesConverter())
                .withValidator(new EmulatesMapValidator());
    }

    /**
     * Creates a new {@link Configuration} instance that has automatically registered all config records that are part
     * of the base packages {@code com.hedera} or {@code com.swirlds}.
     *
     * @return a new {@link Configuration} instance
     */
    @NonNull
    public static Configuration createConfig() {
        return create().getOrCreateConfig();
    }

    /**
     * Convenience method that creates and returns a {@link ConfigProvider} with the configuration of this builder as
     * a {@link com.hedera.node.config.VersionedConfiguration} with version number 0.
     */
    @NonNull
    public static ConfigProvider createConfigProvider() {
        final var config = createConfig();
        final var versioned = new VersionedConfigImpl(config, 0);
        return () -> versioned;
    }
}
