// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.components;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.spi.AppContext.Gossip.UNAVAILABLE_GOSSIP;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.NOOP_FEE_CHARGING;
import static com.hedera.node.app.state.recordcache.schemas.V0490RecordCacheSchema.TRANSACTION_RECEIPTS_STATE_ID;
import static com.swirlds.platform.system.address.AddressBookUtils.endpointFor;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.DaggerHederaInjectionComponent;
import com.hedera.node.app.HederaInjectionComponent;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.blocks.InitialStateHash;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.hints.impl.HintsServiceImpl;
import com.hedera.node.app.history.impl.HistoryLibraryImpl;
import com.hedera.node.app.history.impl.HistoryServiceImpl;
import com.hedera.node.app.ids.AppEntityIdFactory;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.metrics.StoreMetricsServiceImpl;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.services.AppContextImpl;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.services.StartupNetworks;
import com.hedera.node.app.signature.AppSignatureVerifier;
import com.hedera.node.app.signature.impl.SignatureExpanderImpl;
import com.hedera.node.app.signature.impl.SignatureVerifierImpl;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.throttle.Throttle;
import com.hedera.node.app.state.recordcache.RecordCacheService;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.transaction.TransactionPoolNexus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestComponentTest {

    @Mock
    private Platform platform;

    @Mock
    private TransactionPoolNexus transactionPool;

    @Mock
    private Throttle.Factory throttleFactory;

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    private BlockHashSigner blockHashSigner;

    @Mock
    private PlatformStateFacade platformStateFacade;

    private HederaInjectionComponent app;

    private static final Metrics NO_OP_METRICS = new NoOpMetrics();

    private static final NodeInfo DEFAULT_NODE_INFO =
            new NodeInfoImpl(0, asAccount(0L, 0L, 3L), 10, List.of(), Bytes.EMPTY, List.of(), false, null);

    @BeforeEach
    void setUp() {
        final Configuration configuration = HederaTestConfigBuilder.createConfig();
        final PlatformContext platformContext = mock(PlatformContext.class);
        final Metrics metrics = new NoOpMetrics();
        lenient().when(platformContext.getConfiguration()).thenReturn(configuration);

        final var selfNodeInfo = new NodeInfoImpl(
                1L,
                AccountID.newBuilder().accountNum(1001).build(),
                10,
                List.of(endpointFor("127.0.0.1", 50211), endpointFor("127.0.0.1", 23456)),
                Bytes.wrap("cert7"),
                List.of(endpointFor("127.0.0.1", 50211), endpointFor("127.0.0.1", 23456)),
                false,
                null);

        final var configProvider = new ConfigProviderImpl(false);
        final var appContext = new AppContextImpl(
                InstantSource.system(),
                new AppSignatureVerifier(
                        DEFAULT_CONFIG.getConfigData(HederaConfig.class),
                        new SignatureExpanderImpl(),
                        new SignatureVerifierImpl()),
                UNAVAILABLE_GOSSIP,
                () -> configuration,
                () -> DEFAULT_NODE_INFO,
                () -> NO_OP_METRICS,
                throttleFactory,
                () -> NOOP_FEE_CHARGING,
                new AppEntityIdFactory(configuration));
        final var hintsService = new HintsServiceImpl(
                NO_OP_METRICS,
                ForkJoinPool.commonPool(),
                appContext,
                new HintsLibraryImpl(),
                DEFAULT_CONFIG.getConfigData(BlockStreamConfig.class).blockPeriod());
        final var historyService = new HistoryServiceImpl(
                NO_OP_METRICS, ForkJoinPool.commonPool(), appContext, new HistoryLibraryImpl(), DEFAULT_CONFIG);
        app = DaggerHederaInjectionComponent.builder()
                .appContext(appContext)
                .configProviderImpl(configProvider)
                .bootstrapConfigProviderImpl(new BootstrapConfigProviderImpl())
                .fileServiceImpl(new FileServiceImpl())
                .contractServiceImpl(new ContractServiceImpl(appContext, NO_OP_METRICS))
                .utilServiceImpl(new UtilServiceImpl(appContext, (signedTxn, config) -> null))
                .scheduleService(new ScheduleServiceImpl(appContext))
                .initTrigger(InitTrigger.GENESIS)
                .platform(platform)
                .transactionPool(transactionPool)
                .self(selfNodeInfo)
                .currentPlatformStatus(() -> PlatformStatus.ACTIVE)
                .servicesRegistry(mock(ServicesRegistry.class))
                .instantSource(InstantSource.system())
                .softwareVersion(mock(SemanticVersion.class))
                .metrics(metrics)
                .immediateStateChangeListener(new ImmediateStateChangeListener())
                .boundaryStateChangeListener(new BoundaryStateChangeListener(
                        new StoreMetricsServiceImpl(metrics), configProvider::getConfiguration))
                .migrationStateChanges(List.of())
                .hintsService(hintsService)
                .historyService(historyService)
                .initialStateHash(new InitialStateHash(completedFuture(Bytes.EMPTY), 0))
                .networkInfo(mock(NetworkInfo.class))
                .startupNetworks(startupNetworks)
                .throttleFactory(throttleFactory)
                .blockHashSigner(blockHashSigner)
                .hintsService(hintsService)
                .historyService(historyService)
                .platformStateFacade(platformStateFacade)
                .build();

        final var state = new FakeState();
        state.addService(RecordCacheService.NAME, Map.of(TRANSACTION_RECEIPTS_STATE_ID, new ArrayDeque<String>()));
        app.workingStateAccessor().setState(state);
    }

    @Test
    void objectGraphRootsAreAvailable() {
        final IngestInjectionComponent subject =
                app.ingestComponentFactory().get().create();

        assertNotNull(subject.ingestWorkflow());
    }
}
