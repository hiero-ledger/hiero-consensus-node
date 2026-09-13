// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.metrics;

import com.swirlds.base.internal.BaseExecutorFactory;
import com.swirlds.base.internal.observe.BaseExecutorObserver;
import com.swirlds.base.internal.observe.BaseTaskDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small, public-facing registrar that allows external modules to observe base-executor events without
 * depending on internal swirlds packages.
 */
public final class BaseExecutorMetricsRegistrar {

    private BaseExecutorMetricsRegistrar() {}

    /** A lightweight description of a task submitted to the base executor. */
    public static final class TaskInfo {
        private final String type;

        private TaskInfo(final String type) {
            this.type = type;
        }

        public String getType() {
            return type;
        }

        static TaskInfo of(final BaseTaskDefinition def) {
            return new TaskInfo(def.type());
        }
    }

    /** Public observer interface for base-executor events. */
    public interface Observer {
        void onTaskSubmitted(@NonNull TaskInfo taskInfo);

        void onTaskStarted(@NonNull TaskInfo taskInfo);

        void onTaskDone(@NonNull TaskInfo taskInfo, @NonNull Duration duration);

        void onTaskFailed(@NonNull TaskInfo taskInfo, @NonNull Duration duration);
    }

    private static final Map<Observer, BaseExecutorObserver> ADAPTERS = new ConcurrentHashMap<>();

    /**
     * Register an observer. The observer will be adapted to the internal observer and registered with
     * the internal BaseExecutorFactory.
     */
    public static void addObserver(@NonNull final Observer observer) {
        Objects.requireNonNull(observer, "observer must not be null");

        final BaseExecutorObserver adapter = new BaseExecutorObserver() {
            @Override
            public void onTaskSubmitted(@NonNull final BaseTaskDefinition taskDefinition) {
                observer.onTaskSubmitted(TaskInfo.of(taskDefinition));
            }

            @Override
            public void onTaskStarted(@NonNull final BaseTaskDefinition taskDefinition) {
                observer.onTaskStarted(TaskInfo.of(taskDefinition));
            }

            @Override
            public void onTaskDone(@NonNull final BaseTaskDefinition taskDefinition, @NonNull final Duration duration) {
                observer.onTaskDone(TaskInfo.of(taskDefinition), duration);
            }

            @Override
            public void onTaskFailed(
                    @NonNull final BaseTaskDefinition taskDefinition, @NonNull final Duration duration) {
                observer.onTaskFailed(TaskInfo.of(taskDefinition), duration);
            }
        };
        ADAPTERS.put(observer, adapter);
        BaseExecutorFactory.addObserver(adapter);
    }

    /**
     * Removes a previously registered observer.
     */
    public static void removeObserver(@NonNull final Observer observer) {
        Objects.requireNonNull(observer, "observer must not be null");
        final BaseExecutorObserver adapter = ADAPTERS.remove(observer);
        if (adapter != null) {
            BaseExecutorFactory.removeObserver(adapter);
        }
    }
}
