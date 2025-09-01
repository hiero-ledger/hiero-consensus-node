// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl;

import com.hedera.node.app.service.schedule.ScheduleServiceApi;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleCreateHandler;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleDeleteHandler;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleGetInfoHandler;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleHandlers;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleSignHandler;
import com.hedera.node.app.spi.api.ServiceApiProvider;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public interface ScheduleServiceModule {
    @Provides
    @Singleton
    static ScheduleHandlers provideScheduleHandlers(
            ScheduleCreateHandler createHandler,
            ScheduleGetInfoHandler getInfoHandler,
            ScheduleSignHandler signHandler,
            ScheduleDeleteHandler deleteHandler) {
        return new ScheduleHandlers(createHandler, deleteHandler, getInfoHandler, signHandler);
    }

    @Binds
    ServiceApiProvider<ScheduleServiceApi> bindScheduleServiceApiProvider(ScheduleServiceApiProvider factory);
}
