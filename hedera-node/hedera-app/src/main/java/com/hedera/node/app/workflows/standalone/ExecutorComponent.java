// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone;

import com.hedera.node.app.authorization.AuthorizerInjectionModule;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.AppFeeCharging;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.platform.PlatformStateModule;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.services.ServicesInjectionModule;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.throttle.Throttle;
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
import dagger.BindsInstance;
import dagger.Component;
import java.util.function.Consumer;
import javax.inject.Singleton;

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
            PlatformStateModule.class,
        })
public interface ExecutorComponent {
    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder fileServiceImpl(FileServiceImpl fileService);

        @BindsInstance
        Builder scheduleService(ScheduleService scheduleService);

        @BindsInstance
        Builder contractServiceImpl(ContractServiceImpl contractService);

        @BindsInstance
        Builder utilServiceImpl(UtilServiceImpl utilService);

        @BindsInstance
        Builder scheduleServiceImpl(ScheduleServiceImpl scheduleService);

        @BindsInstance
        Builder hintsService(HintsService hintsService);

        @BindsInstance
        Builder historyService(HistoryService historyService);

        @BindsInstance
        Builder configProviderImpl(ConfigProviderImpl configProvider);

        @BindsInstance
        Builder disableThrottles(boolean disableThrottles);

        @BindsInstance
        Builder bootstrapConfigProviderImpl(BootstrapConfigProviderImpl bootstrapConfigProvider);

        @BindsInstance
        Builder metrics(Metrics metrics);

        @BindsInstance
        Builder throttleFactory(Throttle.Factory throttleFactory);

        @BindsInstance
        Builder appContext(AppContext appContext);

        ExecutorComponent build();
    }

    Consumer<State> initializer();

    AppFeeCharging appFeeCharging();

    DispatchProcessor dispatchProcessor();

    StandaloneNetworkInfo stateNetworkInfo();

    ExchangeRateManager exchangeRateManager();

    ThrottleServiceManager throttleServiceManager();

    StandaloneDispatchFactory standaloneDispatchFactory();

    TransactionChecker transactionChecker();
}
