// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.deleteschedule.DeleteScheduleTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledInfoTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.hasschedulecapacity.HasScheduleCapacityTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulecall.ScheduleCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulenative.ScheduleNativeTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.signschedule.SignScheduleTranslator;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Provides the {@link CallTranslator} implementations for the HSS system contract.
 */
@Module
public interface HssTranslatorsModule {
    @Provides
    @Singleton
    @Named("HssTranslators")
    static List<CallTranslator<HssCallAttempt>> provideCallAttemptTranslators(
            @NonNull @Named("HssTranslators") final Set<CallTranslator<HssCallAttempt>> translators) {
        return List.copyOf(translators);
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HssTranslators")
    static CallTranslator<HssCallAttempt> provideSignScheduleTranslator(
            @NonNull final SignScheduleTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HssTranslators")
    static CallTranslator<HssCallAttempt> provideScheduleNativeTranslator(
            @NonNull final ScheduleNativeTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HssTranslators")
    static CallTranslator<HssCallAttempt> provideGetScheduledInfoTranslator(
            @NonNull final GetScheduledInfoTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HssTranslators")
    static CallTranslator<HssCallAttempt> provideScheduleCallTranslator(
            @NonNull final ScheduleCallTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HssTranslators")
    static CallTranslator<HssCallAttempt> provideDeleteScheduleTranslator(
            @NonNull final DeleteScheduleTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("HssTranslators")
    static CallTranslator<HssCallAttempt> provideHasScheduleCapacityTranslator(
            @NonNull final HasScheduleCapacityTranslator translator) {
        return translator;
    }
}
