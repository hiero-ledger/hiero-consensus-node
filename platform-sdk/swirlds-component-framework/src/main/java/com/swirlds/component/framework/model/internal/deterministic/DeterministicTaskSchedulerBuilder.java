// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.deterministic;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.DIRECT_THREADSAFE;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.NO_OP;

import com.swirlds.common.metrics.extensions.FractionalTimer;
import com.swirlds.common.metrics.extensions.NoOpFractionalTimer;
import com.swirlds.component.framework.model.DeterministicWiringModel;
import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.internal.AbstractTaskSchedulerBuilder;
import com.swirlds.component.framework.schedulers.internal.DirectTaskScheduler;
import com.swirlds.component.framework.schedulers.internal.NoOpTaskScheduler;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * Builds schedulers for a {@link DeterministicWiringModel}.
 *
 * @param <OUT>
 */
public class DeterministicTaskSchedulerBuilder<OUT> extends AbstractTaskSchedulerBuilder<OUT> {

    private final Consumer<Runnable> submitWork;

    /**
     * Constructor.
     *
     * @param metrics the metrics
     * @param model           the wiring model
     * @param name            the name of the task scheduler. Used for metrics and debugging. Must be unique. Must only
     *                        contain alphanumeric characters and underscores.
     * @param submitWork      a method where all work should be submitted
     */
    public DeterministicTaskSchedulerBuilder(
            @NonNull final Metrics metrics,
            @NonNull final TraceableWiringModel model,
            @NonNull final String name,
            @NonNull final Consumer<Runnable> submitWork) {
        super(metrics, model, name, ForkJoinPool.commonPool());
        this.submitWork = Objects.requireNonNull(submitWork);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public TaskScheduler<OUT> build() {

        final boolean insertionIsBlocking = unhandledTaskCapacity != UNLIMITED_CAPACITY || externalBackPressure;

        final Counters counters = buildCounters();
        final FractionalTimer busyFractionTimer = NoOpFractionalTimer.getInstance();

        final TaskScheduler<OUT> scheduler =
                switch (type) {
                    case CONCURRENT, SEQUENTIAL, SEQUENTIAL_THREAD -> new DeterministicTaskScheduler<>(
                            model,
                            name,
                            type,
                            counters.onRamp(),
                            counters.offRamp(),
                            unhandledTaskCapacity,
                            flushingEnabled,
                            squelchingEnabled,
                            insertionIsBlocking,
                            submitWork);
                    case DIRECT, DIRECT_THREADSAFE -> new DirectTaskScheduler<>(
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

        return scheduler;
    }
}
