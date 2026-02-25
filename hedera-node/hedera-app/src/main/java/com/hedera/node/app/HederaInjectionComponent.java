// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.authorization.AuthorizerInjectionModule;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.BlockStreamModule;
import com.hedera.node.app.blocks.InitialStateHash;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.components.IngestInjectionComponent;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.AppFeeCharging;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.grpc.GrpcInjectionModule;
import com.hedera.node.app.grpc.GrpcServerManager;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.info.CurrentPlatformStatus;
import com.hedera.node.app.metrics.MetricsInjectionModule;
import com.hedera.node.app.platform.PlatformModule;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.quiescence.TxPipelineTracker;
import com.hedera.node.app.records.BlockRecordInjectionModule;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.addressbook.impl.AddressBookServiceImpl;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.networkadmin.impl.NetworkServiceImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.services.NodeRewardManager;
import com.hedera.node.app.services.ServicesInjectionModule;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.app.spi.throttle.ScheduleThrottle;
import com.hedera.node.app.state.HederaStateInjectionModule;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.throttle.ThrottleServiceModule;
import com.hedera.node.app.workflows.FacilityInitModule;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.WorkflowsInjectionModule;
import com.hedera.node.app.workflows.handle.HandleWorkflow;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.ingest.SubmissionManager;
import com.hedera.node.app.workflows.prehandle.PreHandleWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.node.app.workflows.query.annotations.OperatorQueries;
import com.hedera.node.app.workflows.query.annotations.UserQueries;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.listeners.ReconnectCompleteListener;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteListener;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.state.notifications.AsyncFatalIssListener;
import dagger.BindsInstance;
import dagger.Component;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.Charset;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.hiero.consensus.transaction.TransactionPoolNexus;

/**
 * The infrastructure used to implement the platform contract for a Hedera Services node.
 */
@Singleton
@Component(
        modules = {
            ServicesInjectionModule.class,
            WorkflowsInjectionModule.class,
            HederaStateInjectionModule.class,
            GrpcInjectionModule.class,
            MetricsInjectionModule.class,
            AuthorizerInjectionModule.class,
            BlockRecordInjectionModule.class,
            BlockStreamModule.class,
            PlatformModule.class,
            ThrottleServiceModule.class,
            FacilityInitModule.class
        })
public interface HederaInjectionComponent {
    InitTrigger initTrigger();

    Provider<IngestInjectionComponent.Factory> ingestComponentFactory();

    WorkingStateAccessor workingStateAccessor();

    FacilityInitModule.FacilityInitializer initializer();

    RecordCache recordCache();

    GrpcServerManager grpcServerManager();

    Supplier<Charset> nativeCharset();

    NetworkInfo networkInfo();

    AppFeeCharging appFeeCharging();

    @Nullable
    AtomicBoolean systemEntitiesCreationFlag();

    TransactionChecker transactionChecker();

    PreHandleWorkflow preHandleWorkflow();

    HandleWorkflow handleWorkflow();

    IngestWorkflow ingestWorkflow();

    TxPipelineTracker txPipelineTracker();

    @UserQueries
    QueryWorkflow queryWorkflow();

    @OperatorQueries
    QueryWorkflow operatorQueryWorkflow();

    BlockRecordManager blockRecordManager();

    BlockNodeConnectionManager blockNodeConnectionManager();

    BlockBufferService blockBufferService();

    BlockStreamManager blockStreamManager();

    NodeRewardManager nodeRewardManager();

    FeeManager feeManager();

    ExchangeRateManager exchangeRateManager();

    ThrottleServiceManager throttleServiceManager();

    ReconnectCompleteListener reconnectListener();

    StateWriteToDiskCompleteListener stateWriteToDiskListener();

    SubmissionManager submissionManager();

    AsyncFatalIssListener fatalIssListener();

    CurrentPlatformStatus currentPlatformStatus();

    QuiescenceController quiescenceController();

    SelfNodeAccountIdManager selfNodeAccountIdManager();

    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder tokenServiceImpl(TokenServiceImpl tokenService);

        @BindsInstance
        Builder consensusServiceImpl(ConsensusServiceImpl consensusService);

        @BindsInstance
        Builder utilServiceImpl(UtilServiceImpl utilService);

        @BindsInstance
        Builder networkServiceImpl(NetworkServiceImpl networkService);

        @BindsInstance
        Builder hintsService(HintsService hintsService);

        @BindsInstance
        Builder historyService(HistoryService historyService);

        @BindsInstance
        Builder fileServiceImpl(FileServiceImpl fileService);

        @BindsInstance
        Builder contractServiceImpl(ContractServiceImpl contractService);

        @BindsInstance
        Builder scheduleService(ScheduleServiceImpl scheduleService);

        @BindsInstance
        Builder addressBookService(AddressBookServiceImpl addressBookService);

        @BindsInstance
        Builder configProviderImpl(ConfigProviderImpl configProvider);

        @BindsInstance
        Builder bootstrapConfigProviderImpl(BootstrapConfigProviderImpl bootstrapConfigProvider);

        @BindsInstance
        Builder servicesRegistry(ServicesRegistry registry);

        @BindsInstance
        Builder initTrigger(InitTrigger initTrigger);

        @BindsInstance
        Builder platform(Platform platform);

        @BindsInstance
        Builder transactionPool(TransactionPoolNexus transactionPool);

        @BindsInstance
        Builder self(NodeInfo self);

        @BindsInstance
        Builder currentPlatformStatus(CurrentPlatformStatus currentPlatformStatus);

        @BindsInstance
        Builder blockHashSigner(BlockHashSigner blockHashSigner);

        @BindsInstance
        Builder instantSource(InstantSource instantSource);

        @BindsInstance
        Builder throttleFactory(ScheduleThrottle.Factory throttleFactory);

        @BindsInstance
        Builder softwareVersion(SemanticVersion softwareVersion);

        @BindsInstance
        Builder metrics(Metrics metrics);

        @BindsInstance
        Builder boundaryStateChangeListener(BoundaryStateChangeListener boundaryStateChangeListener);

        @BindsInstance
        Builder immediateStateChangeListener(ImmediateStateChangeListener immediateStateChangeListener);

        @BindsInstance
        Builder migrationStateChanges(List<StateChanges.Builder> migrationStateChanges);

        @BindsInstance
        Builder initialStateHash(InitialStateHash initialStateHash);

        @BindsInstance
        Builder networkInfo(NetworkInfo networkInfo);

        @BindsInstance
        Builder startupNetworks(StartupNetworks startupNetworks);

        @BindsInstance
        Builder appContext(AppContext appContext);

        @BindsInstance
        Builder selfNodeAccountIdManager(SelfNodeAccountIdManager selfNodeAccountIdManager);

        HederaInjectionComponent build();
    }
}
