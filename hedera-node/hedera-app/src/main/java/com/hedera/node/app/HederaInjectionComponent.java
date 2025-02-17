/*
 * Copyright (C) 2021-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app;

import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.annotations.MaxSignedTxnSize;
import com.hedera.node.app.authorization.AuthorizerInjectionModule;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.BlockStreamModule;
import com.hedera.node.app.blocks.InitialStateHash;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.KVStateChangeListener;
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
import com.hedera.node.app.info.InfoInjectionModule;
import com.hedera.node.app.metrics.MetricsInjectionModule;
import com.hedera.node.app.platform.PlatformModule;
import com.hedera.node.app.platform.PlatformStateModule;
import com.hedera.node.app.records.BlockRecordInjectionModule;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.services.ServicesInjectionModule;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.throttle.Throttle;
import com.hedera.node.app.state.HederaStateInjectionModule;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.throttle.ThrottleServiceModule;
import com.hedera.node.app.workflows.FacilityInitModule;
import com.hedera.node.app.workflows.WorkflowsInjectionModule;
import com.hedera.node.app.workflows.handle.HandleWorkflow;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.ingest.SubmissionManager;
import com.hedera.node.app.workflows.prehandle.PreHandleWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.node.app.workflows.query.annotations.OperatorQueries;
import com.hedera.node.app.workflows.query.annotations.UserQueries;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.listeners.ReconnectCompleteListener;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteListener;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.state.notifications.AsyncFatalIssListener;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import dagger.BindsInstance;
import dagger.Component;
import java.nio.charset.Charset;
import java.time.InstantSource;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.inject.Provider;
import javax.inject.Singleton;

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
            InfoInjectionModule.class,
            BlockRecordInjectionModule.class,
            BlockStreamModule.class,
            PlatformModule.class,
            ThrottleServiceModule.class,
            FacilityInitModule.class,
            PlatformStateModule.class
        })
public interface HederaInjectionComponent {
    InitTrigger initTrigger();

    Provider<IngestInjectionComponent.Factory> ingestComponentFactory();

    WorkingStateAccessor workingStateAccessor();

    Consumer<State> initializer();

    RecordCache recordCache();

    GrpcServerManager grpcServerManager();

    Supplier<Charset> nativeCharset();

    NetworkInfo networkInfo();

    AppFeeCharging appFeeCharging();

    PreHandleWorkflow preHandleWorkflow();

    HandleWorkflow handleWorkflow();

    IngestWorkflow ingestWorkflow();

    @UserQueries
    QueryWorkflow queryWorkflow();

    @OperatorQueries
    QueryWorkflow operatorQueryWorkflow();

    BlockRecordManager blockRecordManager();

    BlockStreamManager blockStreamManager();

    FeeManager feeManager();

    ExchangeRateManager exchangeRateManager();

    ThrottleServiceManager throttleServiceManager();

    ReconnectCompleteListener reconnectListener();

    StateWriteToDiskCompleteListener stateWriteToDiskListener();

    SubmissionManager submissionManager();

    AsyncFatalIssListener fatalIssListener();

    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder hintsService(HintsService hintsService);

        @BindsInstance
        Builder historyService(HistoryService historyService);

        @BindsInstance
        Builder fileServiceImpl(FileServiceImpl fileService);

        @BindsInstance
        Builder contractServiceImpl(ContractServiceImpl contractService);

        @BindsInstance
        Builder scheduleService(ScheduleService scheduleService);

        @BindsInstance
        Builder configProviderImpl(ConfigProviderImpl configProvider);

        @BindsInstance
        Builder bootstrapConfigProviderImpl(BootstrapConfigProviderImpl bootstrapConfigProvider);

        @BindsInstance
        Builder servicesRegistry(ServicesRegistry registry);

        @BindsInstance
        Builder initTrigger(InitTrigger initTrigger);

        @BindsInstance
        Builder crypto(Cryptography engine);

        @BindsInstance
        Builder platform(Platform platform);

        @BindsInstance
        Builder self(NodeInfo self);

        @BindsInstance
        Builder maxSignedTxnSize(@MaxSignedTxnSize int maxSignedTxnSize);

        @BindsInstance
        Builder currentPlatformStatus(CurrentPlatformStatus currentPlatformStatus);

        @BindsInstance
        Builder blockHashSigner(BlockHashSigner blockHashSigner);

        @BindsInstance
        Builder instantSource(InstantSource instantSource);

        @BindsInstance
        Builder throttleFactory(Throttle.Factory throttleFactory);

        @BindsInstance
        Builder softwareVersion(SemanticVersion softwareVersion);

        @BindsInstance
        Builder metrics(Metrics metrics);

        @BindsInstance
        Builder boundaryStateChangeListener(BoundaryStateChangeListener boundaryStateChangeListener);

        @BindsInstance
        Builder kvStateChangeListener(KVStateChangeListener kvStateChangeListener);

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

        HederaInjectionComponent build();
    }
}
