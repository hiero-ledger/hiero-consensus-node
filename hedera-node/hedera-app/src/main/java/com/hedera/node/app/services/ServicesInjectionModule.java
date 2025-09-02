// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import com.hedera.node.app.service.file.impl.FileServiceInjectionModule;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.token.impl.TokenServiceInjectionModule;
import dagger.Binds;
import dagger.Module;

/**
 * Dagger module for all services
 */
@Module(
        includes = {
            FileServiceInjectionModule.class,
            TokenServiceInjectionModule.class,
        })
public interface ServicesInjectionModule {
    @Binds
    ScheduleService bindScheduleService(ScheduleServiceImpl impl);
}
