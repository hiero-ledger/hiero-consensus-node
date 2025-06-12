// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.config;

import com.google.auto.service.AutoService;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.hapi.utils.sysfiles.domain.KnownBlockValues;
import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
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
import com.swirlds.config.api.ConfigurationExtension;
import com.swirlds.config.api.validation.ConfigValidator;
import com.swirlds.platform.config.AddressBookConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Sets up configuration for services.
 */
@AutoService(ConfigurationExtension.class)
public class ServicesConfigExtension implements ConfigurationExtension {

    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {

        return Set.of(
                AccountsConfig.class,
                AddressBookConfig.class,
                NodesConfig.class,
                ApiPermissionConfig.class,
                AutoRenew2Config.class,
                AutoRenewConfig.class,
                BalancesConfig.class,
                BlockRecordStreamConfig.class,
                BlockStreamConfig.class,
                BlockNodeConnectionConfig.class,
                BootstrapConfig.class,
                CacheConfig.class,
                ConsensusConfig.class,
                ContractsConfig.class,
                EntitiesConfig.class,
                ExpiryConfig.class,
                FeesConfig.class,
                FilesConfig.class,
                GrpcConfig.class,
                HederaConfig.class,
                LedgerConfig.class,
                NettyConfig.class,
                NetworkAdminConfig.class,
                RatesConfig.class,
                SchedulingConfig.class,
                StakingConfig.class,
                StatsConfig.class,
                TokensConfig.class,
                TopicsConfig.class,
                TraceabilityConfig.class,
                AtomicBatchConfig.class,
                VersionConfig.class,
                TssConfig.class,
                JumboTransactionsConfig.class,
                GrpcUsageTrackerConfig.class,
                OpsDurationConfig.class);
    }

    @NonNull
    public Set<ConverterPair<?>> getConverters() {
        return Set.of(
                ConverterPair.of(CongestionMultipliers.class, new CongestionMultipliersConverter()),
                ConverterPair.of(EntityScaleFactors.class, new EntityScaleFactorsConverter()),
                ConverterPair.of(KnownBlockValues.class, new KnownBlockValuesConverter()),
                ConverterPair.of(ScaleFactor.class, new ScaleFactorConverter()),
                ConverterPair.of(AccountID.class, new AccountIDConverter()),
                ConverterPair.of(ContractID.class, new ContractIDConverter()),
                ConverterPair.of(FileID.class, new FileIDConverter()),
                ConverterPair.of(PermissionedAccountsRange.class, new PermissionedAccountsRangeConverter()),
                ConverterPair.of(SemanticVersion.class, new SemanticVersionConverter()),
                ConverterPair.of(LongPair.class, new LongPairConverter()),
                ConverterPair.of(KeyValuePair.class, new KeyValuePairConverter()),
                ConverterPair.of(HederaFunctionalitySet.class, new FunctionalitySetConverter()),
                ConverterPair.of(Bytes.class, new BytesConverter()));
    }

    @NonNull
    public Set<ConfigValidator> getValidators() {
        return Set.of(new EmulatesMapValidator());
    }
}
