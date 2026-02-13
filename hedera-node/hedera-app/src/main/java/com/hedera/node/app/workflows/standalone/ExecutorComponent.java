// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone;

import com.hedera.node.app.authorization.AuthorizerInjectionModule;
import com.hedera.node.app.blocks.BlockStreamModule;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.AppFeeCharging;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.service.addressbook.impl.AddressBookServiceImpl;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.networkadmin.impl.NetworkServiceImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.services.ServicesInjectionModule;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.app.spi.throttle.ScheduleThrottle;
import com.hedera.node.app.state.HederaStateInjectionModule;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.throttle.ThrottleServiceModule;
import com.hedera.node.app.workflows.FacilityInitModule;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.handle.DispatchProcessor;
import com.hedera.node.app.workflows.handle.HandleWorkflowModule;
import com.hedera.node.app.workflows.prehandle.PreHandleWorkflowInjectionModule;
import com.hedera.node.app.workflows.standalone.impl.StandaloneDispatchFactory;
import com.hedera.node.app.workflows.standalone.impl.StandaloneModule;
import com.hedera.node.app.workflows.standalone.impl.StandaloneNetworkInfo;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import dagger.BindsInstance;
import dagger.Component;
import javax.inject.Singleton;
import org.hiero.interledger.clpr.impl.ClprModule;

/**
 * A component that provides DI for construction of {@link StandaloneDispatchFactory}, {@link StandaloneNetworkInfo}, and
 * {@link DispatchProcessor} instances needed to execute standalone transactions against a {@link State}.
 */
@Singleton
@Component(
        modules = {
            StandaloneModule.class,
            HandleWorkflowModule.class,
            AuthorizerInjectionModule.class,
            PreHandleWorkflowInjectionModule.class,
            ServicesInjectionModule.class,
            HederaStateInjectionModule.class,
            ThrottleServiceModule.class,
            FacilityInitModule.class,
            BlockStreamModule.class,
            ClprModule.class
        })
public interface ExecutorComponent {
    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder tokenServiceImpl(TokenServiceImpl tokenService);

        @BindsInstance
        Builder consensusServiceImpl(ConsensusServiceImpl consensusServiceImpl);

        @BindsInstance
        Builder fileServiceImpl(FileServiceImpl fileService);

        @BindsInstance
        Builder contractServiceImpl(ContractServiceImpl contractService);

        @BindsInstance
        Builder utilServiceImpl(UtilServiceImpl utilService);

        @BindsInstance
        Builder networkServiceImpl(NetworkServiceImpl networkService);

        @BindsInstance
        Builder scheduleServiceImpl(ScheduleServiceImpl scheduleService);

        @BindsInstance
        Builder hintsService(HintsService hintsService);

        @BindsInstance
        Builder historyService(HistoryService historyService);

        @BindsInstance
        Builder addressBookService(AddressBookServiceImpl addressBookService);

        @BindsInstance
        Builder configProviderImpl(ConfigProviderImpl configProvider);

        @BindsInstance
        Builder disableThrottles(boolean disableThrottles);

        @BindsInstance
        Builder bootstrapConfigProviderImpl(BootstrapConfigProviderImpl bootstrapConfigProvider);

        @BindsInstance
        Builder metrics(Metrics metrics);

        @BindsInstance
        Builder throttleFactory(ScheduleThrottle.Factory throttleFactory);

        @BindsInstance
        Builder appContext(AppContext appContext);

        @BindsInstance
        Builder selfNodeAccountIdManager(SelfNodeAccountIdManager selfNodeAccountIdManager);

        @BindsInstance
        Builder stateLifecycleManager(StateLifecycleManager stateLifecycleManager);

        ExecutorComponent build();
    }

    FacilityInitModule.FacilityInitializer initializer();

    AppFeeCharging appFeeCharging();

    DispatchProcessor dispatchProcessor();

    StandaloneNetworkInfo stateNetworkInfo();

    ExchangeRateManager exchangeRateManager();

    ThrottleServiceManager throttleServiceManager();

    StandaloneDispatchFactory standaloneDispatchFactory();

    TransactionChecker transactionChecker();

    FeeManager feeManager();
}
