// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.BACKEND_THROTTLE;

import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.throttle.ThrottleMetrics;
import com.hedera.node.app.throttle.annotations.BackendThrottle;
import com.hedera.node.app.workflows.handle.HandleWorkflowModule;
import com.hedera.node.app.workflows.ingest.IngestWorkflowInjectionModule;
import com.hedera.node.app.workflows.prehandle.PreHandleWorkflowInjectionModule;
import com.hedera.node.app.workflows.query.QueryWorkflowInjectionModule;
import com.hedera.node.config.ConfigProvider;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.InitTrigger;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Singleton;

/**
 * Dagger module for all workflows
 */
@Module(
        includes = {
            HandleWorkflowModule.class,
            IngestWorkflowInjectionModule.class,
            PreHandleWorkflowInjectionModule.class,
            QueryWorkflowInjectionModule.class
        })
public interface WorkflowsInjectionModule {
    @Provides
    @Nullable
    @Singleton
    static AtomicBoolean provideMaybeSystemEntitiesCreatedFlag(@NonNull final InitTrigger initTrigger) {
        return initTrigger == InitTrigger.GENESIS ? new AtomicBoolean(false) : null;
    }

    @Provides
    @Singleton
    @BackendThrottle
    static ThrottleAccumulator provideBackendThrottleAccumulator(
            @NonNull final ConfigProvider configProvider, @NonNull final Metrics metrics) {
        final var throttleMetrics = new ThrottleMetrics(metrics, BACKEND_THROTTLE);
        return new ThrottleAccumulator(
                () -> 1,
                configProvider::getConfiguration,
                BACKEND_THROTTLE,
                throttleMetrics,
                ThrottleAccumulator.Verbose.YES);
    }
}
