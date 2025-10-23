// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl;

import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.schedule.ScheduleServiceApi;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleHandlers;
import com.hedera.node.app.spi.api.ServiceApiProvider;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.throttle.Throttle;
import dagger.BindsInstance;
import dagger.Component;
import java.time.InstantSource;
import java.util.function.Supplier;
import javax.inject.Singleton;

@Singleton
@Component(modules = ScheduleServiceModule.class)
public interface ScheduleServiceComponent {
    /**
     * A factory for creating a {@link ScheduleServiceComponent}.
     */
    @Component.Factory
    interface Factory {
        /**
         * @param instantSource the source of the current instant
         * @param throttleFactory a factory for creating throttles
         * @param entityIdFactory a factory for creating entity IDs
         * @param baseFeeCharging a supplier for the base fee charging strategy
         * @return the schedule service component
         */
        ScheduleServiceComponent create(
                @BindsInstance InstantSource instantSource,
                @BindsInstance Throttle.Factory throttleFactory,
                @BindsInstance EntityIdFactory entityIdFactory,
                @BindsInstance Supplier<FeeCharging> baseFeeCharging);
    }

    /**
     * Provides the {@link ScheduleServiceApi} provider.
     */
    ServiceApiProvider<ScheduleServiceApi> scheduleServiceApiProvider();

    /**
     * @return all schedule transaction handlers
     */
    ScheduleHandlers handlers();
}
