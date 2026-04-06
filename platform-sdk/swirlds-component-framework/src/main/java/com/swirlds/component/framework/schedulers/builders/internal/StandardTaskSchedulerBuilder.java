// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.builders.internal;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.DIRECT_THREADSAFE;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.NO_OP;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.StandardWiringModel;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.schedulers.internal.ConcurrentTaskScheduler;
import com.swirlds.component.framework.schedulers.internal.DirectTaskScheduler;
import com.swirlds.component.framework.schedulers.internal.NoOpTaskScheduler;
import com.swirlds.component.framework.schedulers.internal.SequentialTaskScheduler;
import com.swirlds.component.framework.schedulers.internal.SequentialThreadTaskScheduler;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.consensus.metrics.FunctionGauge;
import org.hiero.consensus.metrics.extensions.FractionalTimer;
import org.hiero.consensus.metrics.extensions.NoOpFractionalTimer;
import org.hiero.consensus.metrics.extensions.StandardFractionalTimer;

/**
 * A builder for {@link TaskScheduler}s.
 *
 * @param <OUT> the output type of the primary output wire for this task scheduler (use {@link Void} for a scheduler
 *              with no output)
 */
public class StandardTaskSchedulerBuilder<OUT> extends AbstractTaskSchedulerBuilder<OUT> {

    protected final Time time;

    /**
     * Constructor.
     *
     * @param time the time
     * @param metrics the metrics
     * @param model           the wiring model
     * @param name            the name of the task scheduler. Used for metrics and debugging. Must be unique. Must only
     *                        contain alphanumeric characters and underscores.
     * @param defaultPool     the default fork join pool, if none is provided then this pool will be used
     */
    public StandardTaskSchedulerBuilder(
            @NonNull final Time time,
            @NonNull final Metrics metrics,
            @NonNull final StandardWiringModel model,
            @NonNull final String name,
            @NonNull final ForkJoinPool defaultPool) {

        super(metrics, model, name, defaultPool);
        this.time = time;
    }

    /**
     * Build an in-flight task counter if enabled.
     *
     * @return the counter, or null if not enabled
     */
    @Nullable
    private AtomicLong buildInflightCount() {
        if (!inflightTaskMetricEnabled || type == NO_OP) {
            return null;
        }
        if (type != TaskSchedulerType.CONCURRENT) {
            throw new IllegalStateException("In-flight task metric is only compatible with concurrent schedulers");
        }
        return new AtomicLong(0);
    }

    /**
     * Build a busy timer if enabled.
     *
     * @return the busy timer, or null if not enabled
     */
    @NonNull
    private FractionalTimer buildBusyTimer() {
        if (!busyFractionMetricEnabled || type == NO_OP) {
            return NoOpFractionalTimer.getInstance();
        }
        if (type == TaskSchedulerType.CONCURRENT) {
            throw new IllegalStateException("Busy fraction metric is not compatible with concurrent schedulers");
        }
        return new StandardFractionalTimer(time);
    }

    /**
     * Register all configured metrics.
     *
     * @param scheduler         the scheduler to register metrics for
     * @param busyFractionTimer the timer that is used to track the fraction of the time that the underlying thread
     *                             is busy
     */
    private void registerMetrics(
            @NonNull final TaskScheduler<OUT> scheduler, @NonNull final FractionalTimer busyFractionTimer) {

        if (type == NO_OP) {
            return;
        }

        if (unhandledTaskMetricEnabled) {
            final FunctionGauge.Config<Long> config = new FunctionGauge.Config<>(
                            "platform", name + "_unhandled_task_count", Long.class, scheduler::getUnprocessedTaskCount)
                    .withDescription(
                            "The number of scheduled tasks that have not been fully handled for the scheduler " + name);
            metrics.getOrCreate(config);
        }

        if (inflightTaskMetricEnabled) {
            final FunctionGauge.Config<Long> inflightConfig = new FunctionGauge.Config<>(
                            "platform", name + "_inflight_task_count", Long.class, scheduler::getInflightTaskCount)
                    .withDescription("The number of tasks currently being executed for the scheduler " + name);
            metrics.getOrCreate(inflightConfig);
        }

        if (busyFractionMetricEnabled) {
            busyFractionTimer.registerMetric(
                    metrics,
                    "platform",
                    name + "_busy_fraction",
                    "Fraction (out of 1.0) of time spent processing tasks for the task scheduler " + name);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public TaskScheduler<OUT> build() {
        final Counters counters = buildCounters();
        final FractionalTimer busyFractionTimer = buildBusyTimer();
        final boolean insertionIsBlocking =
                ((unhandledTaskCapacity != UNLIMITED_CAPACITY) || externalBackPressure) && (type != NO_OP);

        final AtomicLong inflightCount = buildInflightCount();

        final TaskScheduler<OUT> scheduler =
                switch (type) {
                    case CONCURRENT ->
                        new ConcurrentTaskScheduler<>(
                                model,
                                name,
                                pool,
                                buildUncaughtExceptionHandler(),
                                counters.onRamp(),
                                counters.offRamp(),
                                unhandledTaskCapacity,
                                flushingEnabled,
                                squelchingEnabled,
                                insertionIsBlocking,
                                inflightCount);
                    case SEQUENTIAL ->
                        new SequentialTaskScheduler<>(
                                model,
                                name,
                                pool,
                                buildUncaughtExceptionHandler(),
                                counters.onRamp(),
                                counters.offRamp(),
                                busyFractionTimer,
                                unhandledTaskCapacity,
                                flushingEnabled,
                                squelchingEnabled,
                                insertionIsBlocking);
                    case SEQUENTIAL_THREAD ->
                        new SequentialThreadTaskScheduler<>(
                                model,
                                name,
                                buildUncaughtExceptionHandler(),
                                counters.onRamp(),
                                counters.offRamp(),
                                dataCounter,
                                busyFractionTimer,
                                unhandledTaskCapacity,
                                flushingEnabled,
                                squelchingEnabled,
                                insertionIsBlocking);
                    case DIRECT, DIRECT_THREADSAFE ->
                        new DirectTaskScheduler<>(
                                model,
                                name,
                                buildUncaughtExceptionHandler(),
                                counters.onRamp(),
                                counters.offRamp(),
                                squelchingEnabled,
                                busyFractionTimer,
                                type == DIRECT_THREADSAFE);
                    case NO_OP -> new NoOpTaskScheduler<>(model, name, type, flushingEnabled, squelchingEnabled);
                };

        if (type != NO_OP) {
            model.registerScheduler(scheduler, hyperlink);
        }

        registerMetrics(scheduler, busyFractionTimer);

        return scheduler;
    }
}
