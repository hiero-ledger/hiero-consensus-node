// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.builders.internal;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.DIRECT;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.DIRECT_THREADSAFE;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.NO_OP;

import com.swirlds.component.framework.counters.BackpressureObjectCounter;
import com.swirlds.component.framework.counters.MultiObjectCounter;
import com.swirlds.component.framework.counters.NoOpObjectCounter;
import com.swirlds.component.framework.counters.ObjectCounter;
import com.swirlds.component.framework.counters.StandardObjectCounter;
import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.schedulers.ExceptionHandlers;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerBuilder;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.Thread.UncaughtExceptionHandler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.function.ToLongFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A builder for {@link TaskScheduler}s.
 *
 * @param <OUT> the output type of the primary output wire for this task scheduler (use {@link Void} for a scheduler with
 *            no output)
 */
public abstract class AbstractTaskSchedulerBuilder<OUT> implements TaskSchedulerBuilder<OUT> {

    private static final Logger logger = LogManager.getLogger(AbstractTaskSchedulerBuilder.class);

    protected final TraceableWiringModel model;

    protected TaskSchedulerType type = TaskSchedulerType.SEQUENTIAL;
    protected final String name;
    protected long unhandledTaskCapacity = 1;
    protected boolean flushingEnabled = false;
    protected boolean squelchingEnabled = false;
    protected boolean externalBackPressure = false;
    protected ObjectCounter onRamp;
    protected ObjectCounter offRamp;
    protected ForkJoinPool pool;
    protected UncaughtExceptionHandler uncaughtExceptionHandler;
    protected String hyperlink;
    protected ToLongFunction<Object> dataCounter = data -> 1L;

    protected boolean unhandledTaskMetricEnabled = false;
    protected boolean busyFractionMetricEnabled = false;

    protected Duration sleepDuration = Duration.ofNanos(100);

    protected final Metrics metrics;
    /**
     * Constructor.
     *
     * @param metrics the metrics
     * @param model           the wiring model
     * @param name            the name of the task scheduler. Used for metrics and debugging. Must be unique. Must only
     *                        contain alphanumeric characters and underscores.
     * @param defaultPool     the default fork join pool, if none is provided then this pool will be used
     */
    public AbstractTaskSchedulerBuilder(
            @NonNull final Metrics metrics,
            @NonNull final TraceableWiringModel model,
            @NonNull final String name,
            @NonNull final ForkJoinPool defaultPool) {

        this.metrics = Objects.requireNonNull(metrics);
        this.model = Objects.requireNonNull(model);

        // The reason why wire names have a restricted character set is because downstream consumers of metrics
        // are very fussy about what characters are allowed in metric names.
        if (!name.matches("^[a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Illegal name: \"" + name
                    + "\". Task Schedulers name must only contain alphanumeric characters and underscores");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("TaskScheduler name must not be empty");
        }
        this.name = name;
        this.pool = Objects.requireNonNull(defaultPool);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> configure(@NonNull final TaskSchedulerConfiguration configuration) {
        if (configuration.type() != null) {
            withType(configuration.type());
        }
        if (configuration.unhandledTaskCapacity() != null) {
            withUnhandledTaskCapacity(configuration.unhandledTaskCapacity());
        }
        if (configuration.unhandledTaskMetricEnabled() != null) {
            withUnhandledTaskMetricEnabled(configuration.unhandledTaskMetricEnabled());
        }
        if (configuration.busyFractionMetricEnabled() != null) {
            withBusyFractionMetricsEnabled(configuration.busyFractionMetricEnabled());
        }
        if (configuration.flushingEnabled() != null) {
            withFlushingEnabled(configuration.flushingEnabled());
        }
        if (configuration.squelchingEnabled() != null) {
            withSquelchingEnabled(configuration.squelchingEnabled());
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withType(@NonNull final TaskSchedulerType type) {
        this.type = Objects.requireNonNull(type);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withUnhandledTaskCapacity(final long unhandledTaskCapacity) {
        this.unhandledTaskCapacity = unhandledTaskCapacity;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withFlushingEnabled(final boolean requireFlushCapability) {
        this.flushingEnabled = requireFlushCapability;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withSquelchingEnabled(final boolean squelchingEnabled) {
        this.squelchingEnabled = squelchingEnabled;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withOnRamp(@NonNull final ObjectCounter onRamp) {
        this.onRamp = Objects.requireNonNull(onRamp);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withOffRamp(@NonNull final ObjectCounter offRamp) {
        this.offRamp = Objects.requireNonNull(offRamp);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withExternalBackPressure(final boolean externalBackPressure) {
        this.externalBackPressure = externalBackPressure;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withSleepDuration(@NonNull final Duration backpressureSleepDuration) {
        if (backpressureSleepDuration.isNegative()) {
            throw new IllegalArgumentException("Backpressure sleep duration must not be negative");
        }
        this.sleepDuration = backpressureSleepDuration;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withUnhandledTaskMetricEnabled(final boolean enabled) {
        this.unhandledTaskMetricEnabled = enabled;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withBusyFractionMetricsEnabled(final boolean enabled) {
        this.busyFractionMetricEnabled = enabled;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withPool(@NonNull final ForkJoinPool pool) {
        this.pool = Objects.requireNonNull(pool);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withUncaughtExceptionHandler(
            @NonNull final UncaughtExceptionHandler uncaughtExceptionHandler) {
        this.uncaughtExceptionHandler = Objects.requireNonNull(uncaughtExceptionHandler);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withHyperlink(@Nullable final String hyperlink) {
        this.hyperlink = hyperlink;
        return this;
    }

    @Override
    @NonNull
    public AbstractTaskSchedulerBuilder<OUT> withDataCounter(@NonNull ToLongFunction<Object> counter) {
        this.dataCounter = counter;
        return this;
    }

    /**
     * Build an uncaught exception handler if one was not provided.
     *
     * @return the uncaught exception handler
     */
    @NonNull
    protected UncaughtExceptionHandler buildUncaughtExceptionHandler() {
        if (uncaughtExceptionHandler != null) {
            return uncaughtExceptionHandler;
        } else {
            return ExceptionHandlers.defaultExceptionHandler(name);
        }
    }

    protected record Counters(@NonNull ObjectCounter onRamp, @NonNull ObjectCounter offRamp) {}

    /**
     * Combine two counters into one.
     *
     * @param innerCounter the counter needed for internal implementation details, or null if not needed
     * @param outerCounter the counter provided by the outer scope, or null if not provided
     * @return the combined counter, or a no op counter if both are null
     */
    @NonNull
    protected static ObjectCounter combineCounters(
            @Nullable final ObjectCounter innerCounter, @Nullable final ObjectCounter outerCounter) {
        if (innerCounter == null) {
            if (outerCounter == null) {
                return NoOpObjectCounter.getInstance();
            } else {
                return outerCounter;
            }
        } else {
            if (outerCounter == null) {
                return innerCounter;
            } else {
                return new MultiObjectCounter(innerCounter, outerCounter);
            }
        }
    }

    /**
     * Figure out which counters to use for this task scheduler (if any), constructing them if they need to be
     * constructed.
     */
    @NonNull
    protected Counters buildCounters() {
        if (type == NO_OP) {
            return new Counters(NoOpObjectCounter.getInstance(), NoOpObjectCounter.getInstance());
        }

        final ObjectCounter innerCounter;

        // If we need to enforce a maximum capacity, we have no choice but to use a backpressure object counter.
        //
        // If we don't need to enforce a maximum capacity, we need to use a standard object counter if any
        // of the following conditions are true:
        //  - we have unhandled task metrics enabled
        //  - flushing is enabled. This is because our flush implementation is not
        //    compatible with a no-op counter.
        //
        // In all other cases, better to use a no-op counter. Counters have overhead, and if we don't need one
        // then we shouldn't use one.

        if (model.isBackpressureEnabled()
                && unhandledTaskCapacity != UNLIMITED_CAPACITY
                && type != DIRECT
                && type != DIRECT_THREADSAFE) {

            innerCounter = new BackpressureObjectCounter(name, unhandledTaskCapacity, sleepDuration);
        } else if (unhandledTaskMetricEnabled || flushingEnabled) {
            innerCounter = new StandardObjectCounter(sleepDuration);
        } else {
            innerCounter = null;
        }

        return new Counters(combineCounters(innerCounter, onRamp), combineCounters(innerCounter, offRamp));
    }
}
