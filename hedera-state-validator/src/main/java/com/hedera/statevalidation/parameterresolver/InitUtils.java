// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.parameterresolver;

import static com.hedera.node.app.spi.fees.NoopFeeCharging.NOOP_FEE_CHARGING;
import static com.hedera.statevalidation.parameterresolver.StateResolver.readVersion;
import static com.hedera.statevalidation.validators.Constants.FILE_CHANNELS;
import static com.hedera.statevalidation.validators.Constants.STATE_DIR;
import static com.swirlds.platform.state.service.PlatformStateService.PLATFORM_STATE_SERVICE;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.FeeService;
import com.hedera.node.app.fixtures.state.FakeStartupNetworks;
import com.hedera.node.app.hapi.utils.sysfiles.domain.KnownBlockValues;
import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.hints.impl.HintsServiceImpl;
import com.hedera.node.app.ids.AppEntityIdFactory;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.service.addressbook.impl.AddressBookServiceImpl;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.networkadmin.impl.FreezeServiceImpl;
import com.hedera.node.app.service.networkadmin.impl.NetworkServiceImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.services.AppContextImpl;
import com.hedera.node.app.services.OrderedServiceMigrator;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.node.app.signature.AppSignatureVerifier;
import com.hedera.node.app.signature.impl.SignatureExpanderImpl;
import com.hedera.node.app.signature.impl.SignatureVerifierImpl;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fixtures.info.FakeNetworkInfo;
import com.hedera.node.app.state.merkle.MerkleSchemaRegistry;
import com.hedera.node.app.state.merkle.SchemaApplications;
import com.hedera.node.app.state.recordcache.RecordCacheService;
import com.hedera.node.app.throttle.AppThrottleFactory;
import com.hedera.node.app.throttle.CongestionThrottleService;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.workflows.standalone.ExecutorComponent;
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
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.types.CongestionMultipliers;
import com.hedera.node.config.types.EntityScaleFactors;
import com.hedera.node.config.types.HederaFunctionalitySet;
import com.hedera.node.config.types.KeyValuePair;
import com.hedera.node.config.types.LongPair;
import com.hedera.node.config.types.PermissionedAccountsRange;
import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.config.AddressBookConfig;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.disk.OnDiskKeySerializer;
import com.swirlds.state.merkle.disk.OnDiskValueSerializer;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.crypto.config.CryptoConfig;

public class InitUtils {

    private static final Logger log = LogManager.getLogger(InitUtils.class);

    /**
     * The excluded tables were renamed (see https://github.com/hashgraph/hedera-services/pull/16775). However, their metadata
     * still remains till the next version. This confuses the validator as it expects these tables to exist while they don't.
     * Hence, we exclude them manually.
     */
    private static final Set<String> TABLES_TO_EXCLUDE = Set.of(
            "ScheduleService.SCHEDULES_BY_EQUALITY",
            "ScheduleService.SCHEDULES_BY_EXPIRY_SEC",
            "HintsService.PREPROCESSING_VOTES",
            "HintsService.HINTS_KEY_SETS",
            "HintsService.CRS_PUBLICATIONS");

    public static Configuration CONFIGURATION;

    /**
     * This method initializes the configuration of the Merkle tree
     */
    static void initConfiguration() {
        CONFIGURATION = ConfigurationBuilder.create()
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(VirtualMapConfig.class)
                .withConfigDataType(MerkleDbConfig.class)
                .withConfigDataType(CryptoConfig.class)
                .withConfigDataType(StateCommonConfig.class)
                .withConfigDataType(StateConfig.class)
                .withConfigDataType(TemporaryFileConfig.class)
                .withConfigDataType(FilesConfig.class)
                .withConfigDataType(ApiPermissionConfig.class)
                .withConfigDataType(BootstrapConfig.class)
                .withConfigDataType(VersionConfig.class)
                .withConfigDataType(LedgerConfig.class)
                .withConfigDataType(TokensConfig.class)
                .withConfigDataType(AddressBookConfig.class)
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(AccountsConfig.class)
                .withConfigDataType(TssConfig.class)
                .withSource(new SimpleConfigSource().withValue("merkleDb.usePbj", false))
                .withSource(new SimpleConfigSource().withValue("merkleDb.minNumberOfFilesInCompaction", 2))
                .withSource(new SimpleConfigSource().withValue("merkleDb.maxFileChannelsPerFileReader", FILE_CHANNELS))
                .withSource(new SimpleConfigSource().withValue("merkleDb.maxThreadsPerFileChannel", 1))
                .withConverter(CongestionMultipliers.class, new CongestionMultipliersConverter())
                .withConverter(EntityScaleFactors.class, new EntityScaleFactorsConverter())
                .withConverter(KnownBlockValues.class, new KnownBlockValuesConverter())
                .withConverter(ScaleFactor.class, new ScaleFactorConverter())
                .withConverter(AccountID.class, new AccountIDConverter())
                .withConverter(ContractID.class, new ContractIDConverter())
                .withConverter(FileID.class, new FileIDConverter())
                .withConverter(PermissionedAccountsRange.class, new PermissionedAccountsRangeConverter())
                .withConverter(SemanticVersion.class, new SemanticVersionConverter())
                .withConverter(LongPair.class, new LongPairConverter())
                .withConverter(KeyValuePair.class, new KeyValuePairConverter())
                .withConverter(HederaFunctionalitySet.class, new FunctionalitySetConverter())
                .withConverter(Bytes.class, new BytesConverter())
                .build();
    }

    public static Configuration getConfiguration() {
        return CONFIGURATION;
    }

    /**
     * This method initializes all the virtual maps and their data sources
     *
     * @param servicesRegistry the services registry required to build VMs
     * @return the list of virtual maps and their data sources
     */
    static List<VirtualMapAndDataSourceRecord<?, ?>> initVirtualMapRecords(ServicesRegistryImpl servicesRegistry) {
        final Path stateDirPath = Paths.get(STATE_DIR);

        final MerkleDb merkleDb = MerkleDb.getInstance(stateDirPath, CONFIGURATION);
        Map<String, MerkleDbTableConfig> tableConfigByNames = merkleDb.getTableConfigs();
        final var virtualMaps = new ArrayList<VirtualMapAndDataSourceRecord<?, ?>>();

        servicesRegistry.registrations().forEach((registration) -> {
            try {
                var service = registration.service();
                var serviceName = service.getServiceName();
                log.debug("Registering schemas for service {}", serviceName);
                var registry =
                        new MerkleSchemaRegistry(
                                ConstructableRegistry.getInstance(),
                                serviceName,
                                CONFIGURATION,
                                new SchemaApplications()) {
                            @SuppressWarnings({"rawtypes", "unchecked"})
                            @Override
                            public SchemaRegistry register(Schema schema) {
                                schema.statesToCreate().forEach((def) -> {
                                    if (!def.onDisk()) {
                                        return;
                                    }
                                    final var md = new StateMetadata<>(serviceName, schema, def);
                                    final var label = StateMetadata.computeLabel(serviceName, def.stateKey());
                                    if (TABLES_TO_EXCLUDE.contains(label)) {
                                        return;
                                    }
                                    MerkleDbTableConfig tableConfig = tableConfigByNames.get(label);
                                    final var keySerializer = new OnDiskKeySerializer<>(
                                            md.onDiskKeySerializerClassId(),
                                            md.onDiskKeyClassId(),
                                            md.stateDefinition().keyCodec());
                                    final var valueSerializer = new OnDiskValueSerializer<>(
                                            md.onDiskValueSerializerClassId(),
                                            md.onDiskValueClassId(),
                                            md.stateDefinition().valueCodec());
                                    final var ds = new RestoringMerkleDbDataSourceBuilder<>(stateDirPath, tableConfig);
                                    final var vm =
                                            new VirtualMap(label, keySerializer, valueSerializer, ds, CONFIGURATION);
                                    virtualMaps.add(new VirtualMapAndDataSourceRecord<>(
                                            label,
                                            (MerkleDbDataSource) vm.getDataSource(),
                                            vm,
                                            keySerializer,
                                            valueSerializer));
                                });
                                return null;
                            }
                        };

                service.registerSchemas(registry);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        return virtualMaps;
    }

    /**
     * This method initializes all the services in the registry and by proxy it initilizes all the underlying deserializers
     * to read the state files
     *
     * @return the initialized services registry
     */
    static ServicesRegistryImpl initServiceRegistry() {
        final Configuration bootstrapConfig = new BootstrapConfigProviderImpl().getConfiguration();
        final Configuration config = getConfiguration();
        final Supplier<Configuration> configSupplier = () -> config;
        final ServicesRegistryImpl servicesRegistry =
                new ServicesRegistryImpl(ConstructableRegistry.getInstance(), config);
        final FakeNetworkInfo fakeNetworkInfo = new FakeNetworkInfo();
        final AppContextImpl appContext = new AppContextImpl(
                InstantSource.system(),
                new AppSignatureVerifier(
                        bootstrapConfig.getConfigData(HederaConfig.class),
                        new SignatureExpanderImpl(),
                        new SignatureVerifierImpl()),
                AppContext.Gossip.UNAVAILABLE_GOSSIP,
                configSupplier,
                fakeNetworkInfo::selfNodeInfo,
                NoOpMetrics::new,
                new AppThrottleFactory(
                        configSupplier, () -> null, () -> ThrottleDefinitions.DEFAULT, ThrottleAccumulator::new),
                () -> NOOP_FEE_CHARGING,
                new AppEntityIdFactory(config));
        PlatformStateService.PLATFORM_STATE_SERVICE.setAppVersionFn(v -> readVersion());

        final AtomicReference<ExecutorComponent> componentRef = new AtomicReference<>();
        Set.of(
                        new EntityIdService(),
                        new ConsensusServiceImpl(),
                        new ContractServiceImpl(appContext, new NoOpMetrics()),
                        new FileServiceImpl(),
                        new FreezeServiceImpl(),
                        new ScheduleServiceImpl(appContext),
                        new TokenServiceImpl(appContext),
                        new UtilServiceImpl(appContext, (signedTxn, conf) -> componentRef
                                .get()
                                .transactionChecker()
                                .parseSignedAndCheck(
                                        signedTxn,
                                        config.getConfigData(HederaConfig.class).nodeTransactionMaxBytes())
                                .txBody()),
                        new RecordCacheService(),
                        new BlockRecordService(),
                        new BlockStreamService(),
                        new FeeService(),
                        new CongestionThrottleService(),
                        new NetworkServiceImpl(),
                        new AddressBookServiceImpl(),
                        new HintsServiceImpl(
                                new NoOpMetrics(),
                                ForkJoinPool.commonPool(),
                                appContext,
                                new HintsLibraryImpl(),
                                bootstrapConfig
                                        .getConfigData(BlockStreamConfig.class)
                                        .blockPeriod()),
                        new RosterService(
                                roster -> true,
                                (r, b) -> {},
                                () -> StateResolver.deserializedSignedState
                                        .reservedSignedState()
                                        .get()
                                        .getState(),
                                PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE),
                        PLATFORM_STATE_SERVICE)
                .forEach(servicesRegistry::register);
        return servicesRegistry;
    }

    /**
     * This method initializes the State API
     */
    static void initServiceMigrator(State state, Configuration configuration, ServicesRegistry servicesRegistry) {
        final var bootstrapConfigProvider = new BootstrapConfigProviderImpl();
        final var serviceMigrator = new OrderedServiceMigrator();
        final var platformFacade = PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
        final var deserializedVersion = platformFacade.creationSoftwareVersionOf(state);
        final var version = getNodeStartupVersion(bootstrapConfigProvider.getConfiguration());
        serviceMigrator.doMigrations(
                (MerkleNodeState) state,
                servicesRegistry,
                deserializedVersion,
                version,
                configuration,
                configuration,
                new NoOpMetrics(),
                new FakeStartupNetworks(Network.newBuilder().build()),
                new StoreMetricsServiceImpl(new NoOpMetrics()),
                new ConfigProviderImpl(),
                platformFacade);
    }

    private static SemanticVersion getNodeStartupVersion(final Configuration config) {
        return readVersion();
    }
}
