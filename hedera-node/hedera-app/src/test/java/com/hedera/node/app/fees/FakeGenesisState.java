// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.hedera.node.app.spi.AppContext.Gossip.UNAVAILABLE_GOSSIP;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.UNIVERSAL_NOOP_FEE_CHARGING;
import static com.hedera.node.app.util.FileUtilities.createFileID;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fixtures.state.FakeServiceMigrator;
import com.hedera.node.app.fixtures.state.FakeServicesRegistry;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.AddressBookServiceImpl;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.service.entityid.impl.EntityIdServiceImpl;
import com.hedera.node.app.service.entityid.impl.WritableEntityIdStoreImpl;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.app.service.networkadmin.impl.FreezeServiceImpl;
import com.hedera.node.app.service.networkadmin.impl.NetworkServiceImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.services.AppContextImpl;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.app.state.recordcache.RecordCacheService;
import com.hedera.node.app.throttle.AppThrottleFactory;
import com.hedera.node.app.throttle.CongestionThrottleService;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.State;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class FakeGenesisState {
    private static final EntityIdFactory idFactory = new AppEntityIdFactory(DEFAULT_CONFIG);

    @Mock
    private SignatureVerifier signatureVerifier;

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    private StoreMetricsServiceImpl storeMetricsService;

    @Mock
    private ConfigProviderImpl configProvider;

    private static final NodeInfo DEFAULT_NODE_INFO =
            new NodeInfoImpl(0, idFactory.newAccountId(3L), 10, List.of(), Bytes.EMPTY, List.of(), true, null);
    public static final Metrics NO_OP_METRICS = new NoOpMetrics();

    public static State make(Map<String, String> overrides) {
        final var state = new FakeGenesisState();
        MockitoAnnotations.openMocks(state);
        final var state2 = state.genesisState(overrides);
        return state2;
    }

    public VirtualMapState genesisState(@NonNull final Map<String, String> overrides) {
        final var state = new FakeState();
        final var configBuilder = HederaTestConfigBuilder.create();
        overrides.forEach(configBuilder::withValue);
        final var config = configBuilder.getOrCreateConfig();
        final var servicesRegistry = new FakeServicesRegistry();
        final var appContext = new AppContextImpl(
                InstantSource.system(),
                signatureVerifier,
                UNAVAILABLE_GOSSIP,
                () -> config,
                () -> DEFAULT_NODE_INFO,
                () -> NO_OP_METRICS,
                new AppThrottleFactory(
                        () -> config, () -> state, () -> ThrottleDefinitions.DEFAULT, ThrottleAccumulator::new),
                () -> UNIVERSAL_NOOP_FEE_CHARGING,
                new AppEntityIdFactory(config));
        registerServices(appContext, servicesRegistry);
        final var migrator = new FakeServiceMigrator();
        final var bootstrapConfig = new BootstrapConfigProviderImpl().getConfiguration();
        migrator.doMigrations(
                state,
                servicesRegistry,
                null,
                bootstrapConfig.getConfigData(VersionConfig.class).servicesVersion(),
                new ConfigProviderImpl().getConfiguration(),
                config,
                startupNetworks,
                storeMetricsService,
                configProvider,
                InitTrigger.GENESIS);
        for (final var r : servicesRegistry.registrations()) {
            final var service = r.service();
            // Maybe EmptyWritableStates if the service's schemas register no state definitions at all
            final var writableStates = state.getWritableStates(service.getServiceName());
            service.doGenesisSetup(writableStates, config);
            if (writableStates instanceof CommittableWritableStates committable) {
                committable.commit();
            }
        }
        // Create a node
        final var nodeWritableStates = state.getWritableStates(AddressBookService.NAME);
        final var nodes = nodeWritableStates.<EntityNumber, Node>get(NODES_STATE_ID);
        nodes.put(
                new EntityNumber(0),
                Node.newBuilder()
                        .accountId(appContext.idFactory().newAccountId(3L))
                        .build());
        ((CommittableWritableStates) nodeWritableStates).commit();
        final var writableStates = state.getWritableStates(FileService.NAME);
        final var readableStates = state.getReadableStates(AddressBookService.NAME);
        final var entityIdStore = new WritableEntityIdStoreImpl(state.getWritableStates(EntityIdService.NAME));
        entityIdStore.adjustEntityCount(EntityType.NODE, 1);
        entityIdStore.incrementHighestNodeIdAndGet();
        final var nodeStore = new ReadableNodeStoreImpl(readableStates, entityIdStore);
        final var files = writableStates.<FileID, File>get(V0490FileSchema.FILES_STATE_ID);
        genesisContentProviders(nodeStore, config).forEach((fileNum, provider) -> {
            final var fileId = createFileID(fileNum, config);
            files.put(
                    fileId,
                    File.newBuilder()
                            .fileId(fileId)
                            .keys(KeyList.DEFAULT)
                            .contents(provider.apply(config))
                            .build());
        });
        final var ledgerConfig = config.getConfigData(LedgerConfig.class);
        final var accountsConfig = config.getConfigData(AccountsConfig.class);
        final var systemKey = Key.newBuilder()
                .ed25519(config.getConfigData(BootstrapConfig.class).genesisPublicKey())
                .build();
        final var accounts =
                state.getWritableStates(TokenService.NAME).<AccountID, Account>get(V0490TokenSchema.ACCOUNTS_STATE_ID);
        // Create the system accounts
        for (int i = 1, n = ledgerConfig.numSystemAccounts(); i <= n; i++) {
            final var accountId = AccountID.newBuilder().accountNum(i).build();
            accounts.put(
                    accountId,
                    Account.newBuilder()
                            .accountId(accountId)
                            .key(systemKey)
                            .expirationSecond(Long.MAX_VALUE)
                            .tinybarBalance(
                                    (long) i == accountsConfig.treasury() ? ledgerConfig.totalTinyBarFloat() : 0L)
                            .build());
        }
        for (final long num : List.of(800L, 801L)) {
            final var accountId = AccountID.newBuilder().accountNum(num).build();
            accounts.put(
                    accountId,
                    Account.newBuilder()
                            .accountId(accountId)
                            .key(systemKey)
                            .expirationSecond(Long.MAX_VALUE)
                            .tinybarBalance(0L)
                            .build());
        }
        ((CommittableWritableStates) writableStates).commit();
        return state;
    }

    private void registerServices(
            @NonNull final AppContext appContext, @NonNull final ServicesRegistry servicesRegistry) {
        // Register all service schema RuntimeConstructable factories before platform init
        Set.of(
                        new EntityIdServiceImpl(),
                        new ConsensusServiceImpl(),
                        new ContractServiceImpl(appContext, NO_OP_METRICS),
                        new FileServiceImpl(),
                        new FreezeServiceImpl(),
                        new ScheduleServiceImpl(appContext),
                        new TokenServiceImpl(appContext),
                        new UtilServiceImpl(appContext, (signedTxn, config) -> null),
                        new RecordCacheService(),
                        new BlockRecordService(),
                        new BlockStreamService(),
                        new FeeService(),
                        new CongestionThrottleService(),
                        new NetworkServiceImpl(),
                        new AddressBookServiceImpl())
                .forEach(servicesRegistry::register);
    }

    private Map<Long, Function<Configuration, Bytes>> genesisContentProviders(
            @NonNull final ReadableNodeStore nodeStore, @NonNull final Configuration config) {
        final var genesisSchema = new V0490FileSchema();
        final var filesConfig = config.getConfigData(FilesConfig.class);
        return Map.of(
                filesConfig.addressBook(), ignore -> genesisSchema.nodeStoreAddressBook(nodeStore),
                filesConfig.nodeDetails(), ignore -> genesisSchema.nodeStoreNodeDetails(nodeStore),
                filesConfig.feeSchedules(), genesisSchema::genesisFeeSchedules,
                filesConfig.simpleFeesSchedules(), genesisSchema::genesisSimpleFeesSchedules,
                filesConfig.exchangeRates(), genesisSchema::genesisExchangeRatesBytes,
                filesConfig.networkProperties(), genesisSchema::genesisNetworkProperties,
                filesConfig.hapiPermissions(), genesisSchema::genesisHapiPermissions,
                filesConfig.throttleDefinitions(), genesisSchema::genesisThrottleDefinitions);
    }
}
