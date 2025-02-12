/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.workflows.standalone;

import com.hedera.node.app.annotations.MaxSignedTxnSize;
import com.hedera.node.app.authorization.AuthorizerInjectionModule;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.AppFeeCharging;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.platform.PlatformStateModule;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.services.ServicesInjectionModule;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.throttle.Throttle;
import com.hedera.node.app.state.HederaStateInjectionModule;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.throttle.ThrottleServiceModule;
import com.hedera.node.app.workflows.FacilityInitModule;
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
            PlatformStateModule.class
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
        Builder scheduleServiceImpl(ScheduleServiceImpl scheduleService);

        @BindsInstance
        Builder hintsService(HintsService hintsService);

        @BindsInstance
        Builder configProviderImpl(ConfigProviderImpl configProvider);

        @BindsInstance
        Builder bootstrapConfigProviderImpl(BootstrapConfigProviderImpl bootstrapConfigProvider);

        @BindsInstance
        Builder metrics(Metrics metrics);

        @BindsInstance
        Builder throttleFactory(Throttle.Factory throttleFactory);

        @BindsInstance
        Builder maxSignedTxnSize(@MaxSignedTxnSize int maxSignedTxnSize);

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
}
