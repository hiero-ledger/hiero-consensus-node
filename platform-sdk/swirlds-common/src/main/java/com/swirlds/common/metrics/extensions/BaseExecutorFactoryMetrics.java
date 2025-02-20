// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.extensions;

import static com.swirlds.base.units.UnitConstants.MILLISECOND_UNIT;

import com.swirlds.base.internal.BaseExecutorFactory;
import com.swirlds.base.internal.observe.BaseExecutorObserver;
import com.swirlds.base.internal.observe.BaseTaskDefinition;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class installs metrics for the {@link BaseExecutorFactory}.
 */
public class BaseExecutorFactoryMetrics {

    /**
     * Prefix for all metrics
     */
    public static final String BASE_EXECUTOR = "base_executor";

    private final TaskExecutionTimeMetric taskExecutionTimeMetric;

    private final TaskExecutionTimeMetric taskDoneExecutionTimeMetric;

    private final TaskExecutionTimeMetric taskFailExecutionTimeMetric;

    private final Counter taskCountAccumulator;

    private final Counter taskExecutionCountAccumulator;

    private final Counter tasksDoneCountAccumulator;

    private final Counter tasksFailedCountAccumulator;

    /**
     * Creates a new instance and installs metrics for the {@link BaseExecutorFactory}.
     *
     * @param metrics the metrics system
     */
    public BaseExecutorFactoryMetrics(@NonNull final Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics must not be null");

        final Counter.Config taskCountAccumulatorConfig = new Counter.Config(BASE_EXECUTOR, "count")
                .withUnit("tasks")
                .withDescription("The number of tasks submitted to the base executor");
        taskCountAccumulator = metrics.getOrCreate(taskCountAccumulatorConfig);

        final Counter.Config taskExecutionCountAccumulatorConfig = new Counter.Config(BASE_EXECUTOR, "execution_count")
                .withUnit("tasks")
                .withDescription("The number of tasks executed by the base executor");
        taskExecutionCountAccumulator = metrics.getOrCreate(taskExecutionCountAccumulatorConfig);

        final Counter.Config tasksDoneCountAccumulatorConfig = new Counter.Config(BASE_EXECUTOR, "done_count")
                .withUnit("tasks")
                .withDescription("The number of tasks executed successfully by the base executor");
        tasksDoneCountAccumulator = metrics.getOrCreate(tasksDoneCountAccumulatorConfig);

        final Counter.Config tasksFailedCountAccumulatorConfig = new Counter.Config(BASE_EXECUTOR, "failed_count")
                .withUnit("tasks")
                .withDescription("The number of tasks failed to execute by the base executor");
        tasksFailedCountAccumulator = metrics.getOrCreate(tasksFailedCountAccumulatorConfig);

        taskExecutionTimeMetric = new TaskExecutionTimeMetric(BASE_EXECUTOR, "task_execution", metrics);

        taskDoneExecutionTimeMetric = new TaskExecutionTimeMetric(BASE_EXECUTOR, "task_done_execution", metrics);

        taskFailExecutionTimeMetric = new TaskExecutionTimeMetric(BASE_EXECUTOR, "task_failed_execution", metrics);

        final BaseExecutorObserver observer = new BaseExecutorObserver() {

            @Override
            public void onTaskSubmitted(@NonNull BaseTaskDefinition taskDefinition) {
                taskCountAccumulator.increment();
            }

            @Override
            public void onTaskStarted(@NonNull BaseTaskDefinition taskDefinition) {
                taskExecutionCountAccumulator.increment();
            }

            @Override
            public void onTaskDone(@NonNull BaseTaskDefinition taskDefinition, @NonNull Duration duration) {
                tasksDoneCountAccumulator.increment();
                taskExecutionTimeMetric.update(duration);
                taskDoneExecutionTimeMetric.update(duration);
            }

            @Override
            public void onTaskFailed(@NonNull BaseTaskDefinition taskDefinition, @NonNull Duration duration) {
                tasksFailedCountAccumulator.increment();
                taskExecutionTimeMetric.update(duration);
                taskFailExecutionTimeMetric.update(duration);
            }
        };
        BaseExecutorFactory.addObserver(observer);
    }

    /**
     * Resets all metrics.
     */
    public void reset() {
        Optional.ofNullable(taskExecutionTimeMetric).ifPresent(TaskExecutionTimeMetric::reset);
        Optional.ofNullable(taskDoneExecutionTimeMetric).ifPresent(TaskExecutionTimeMetric::reset);
        Optional.ofNullable(taskFailExecutionTimeMetric).ifPresent(TaskExecutionTimeMetric::reset);
        Optional.ofNullable(taskCountAccumulator).ifPresent(Counter::reset);
        Optional.ofNullable(taskExecutionCountAccumulator).ifPresent(Counter::reset);
        Optional.ofNullable(tasksDoneCountAccumulator).ifPresent(Counter::reset);
        Optional.ofNullable(tasksFailedCountAccumulator).ifPresent(Counter::reset);
    }

    /**
     * A metric that tracks the execution time of tasks.
     */
    private class TaskExecutionTimeMetric {

        private final AtomicLong callCount;

        private final LongGauge maxMillisMetric;

        private final LongGauge minMillisMetric;

        private final DoubleGauge averageMillisMetric;

        public TaskExecutionTimeMetric(final String category, final String name, @NonNull Metrics metrics) {
            callCount = new AtomicLong(0);

            final LongGauge.Config maxMillisConfig = new LongGauge.Config(BASE_EXECUTOR, name + "_max")
                    .withDescription("The maximum time in milliseconds that a task took to execute")
                    .withUnit(MILLISECOND_UNIT)
                    .withInitialValue(0L);
            maxMillisMetric = metrics.getOrCreate(maxMillisConfig);

            final LongGauge.Config minMillisConfig = new LongGauge.Config(BASE_EXECUTOR, name + "_min")
                    .withDescription("The minimum time in milliseconds that a task took to execute")
                    .withUnit(MILLISECOND_UNIT)
                    .withInitialValue(0L);
            minMillisMetric = metrics.getOrCreate(minMillisConfig);

            final DoubleGauge.Config averageMillisConfig = new DoubleGauge.Config(BASE_EXECUTOR, name + "_avg")
                    .withDescription("The average time in milliseconds that a task took to execute")
                    .withUnit(MILLISECOND_UNIT)
                    .withInitialValue(Double.NaN);
            averageMillisMetric = metrics.getOrCreate(averageMillisConfig);
        }

        public void reset() {
            callCount.set(0);
            maxMillisMetric.set(0L);
            minMillisMetric.set(0L);
            averageMillisMetric.set(Double.NaN);
        }

        public void update(final @NonNull Duration duration) {
            Objects.requireNonNull(duration, "duration must not be null");
            final long prefCount = callCount.getAndIncrement();
            final long millis = duration.toMillis();
            maxMillisMetric.set(Math.max(maxMillisMetric.get(), millis));
            minMillisMetric.set(Math.min(minMillisMetric.get(), millis));
            if (Double.isNaN(averageMillisMetric.get())) {
                averageMillisMetric.set(duration.toMillis());
            }
            try {
                final double newAvg = ((averageMillisMetric.get() * prefCount) + duration.toMillis()) / callCount.get();
                averageMillisMetric.set(newAvg);
            } catch (Exception e) {
                averageMillisMetric.set(Double.NaN);
            }
        }
    }
}
