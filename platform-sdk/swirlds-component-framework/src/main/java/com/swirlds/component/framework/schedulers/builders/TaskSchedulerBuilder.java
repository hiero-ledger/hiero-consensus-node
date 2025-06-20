// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.builders;

import com.swirlds.component.framework.counters.ObjectCounter;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.Thread.UncaughtExceptionHandler;
import java.time.Duration;
import java.util.concurrent.ForkJoinPool;
import java.util.function.ToLongFunction;

/**
 * A builder for {@link TaskScheduler}s.
 *
 * @param <OUT> the output type of the primary output wire for this task scheduler (use {@link Void} for a scheduler
 *              with no output)
 */
public interface TaskSchedulerBuilder<OUT> {

    long UNLIMITED_CAPACITY = -1;

    /**
     * Configure this task scheduler with values from settings.
     *
     * @param configuration the configuration
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> configure(@NonNull TaskSchedulerConfiguration configuration);

    /**
     * Set the type of task scheduler to build. Alters the semantics of the scheduler (i.e. this is not just an internal
     * implementation detail).
     *
     * @param type the type of task scheduler to build
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withType(@NonNull TaskSchedulerType type);

    /**
     * Set the maximum number of permitted scheduled tasks. Default is 1.
     *
     * @param unhandledTaskCapacity the maximum number of permitted unhandled tasks
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withUnhandledTaskCapacity(long unhandledTaskCapacity);

    /**
     * Set whether the task scheduler should enable flushing. Default false. Flushing a scheduler with this disabled
     * will cause the scheduler to throw an exception. Enabling flushing may add overhead.
     *
     * @param requireFlushCapability true if the wire should require flush capability, false otherwise
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withFlushingEnabled(boolean requireFlushCapability);

    /**
     * Set whether the task scheduler should enable squelching. Default false. Enabling squelching may add overhead.
     * <p>
     * IMPORTANT: you must not enable squelching if the scheduler handles tasks that require special cleanup. If
     * squelching is enabled and activated, all existing and incoming tasks will be unceremoniously dumped into the
     * void!
     *
     * @param squelchingEnabled true if the scheduler should enable squelching, false otherwise.
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withSquelchingEnabled(boolean squelchingEnabled);

    /**
     * Specify an object counter that should be notified when data is added to the task scheduler. This is useful for
     * implementing backpressure that spans multiple schedulers.
     *
     * @param onRamp the object counter that should be notified when data is added to the task scheduler
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withOnRamp(@NonNull ObjectCounter onRamp);

    /**
     * Specify an object counter that should be notified when data is removed from the task scheduler. This is useful
     * for implementing backpressure that spans multiple schedulers.
     *
     * @param offRamp the object counter that should be notified when data is removed from the wire
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withOffRamp(@NonNull ObjectCounter offRamp);

    /**
     * If true then the framework will assume that back pressure is being applied via external mechanisms.
     * <p>
     * This method does not change the runtime behavior of the wiring framework. But it does affect cyclical back
     * pressure detection and automatically generated wiring diagrams.
     *
     * @param externalBackPressure true if back pressure is being applied externally, false otherwise
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withExternalBackPressure(boolean externalBackPressure);

    /**
     * If a method needs to block, this is the amount of time that is slept while waiting for the needed condition.
     *
     * @param backpressureSleepDuration the length of time to sleep when backpressure needs to be applied
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withSleepDuration(@NonNull Duration backpressureSleepDuration);

    /**
     * Set whether the unhandled task count metric should be enabled. Default false.
     *
     * @param enabled true if the unhandled task count metric should be enabled, false otherwise
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withUnhandledTaskMetricEnabled(boolean enabled);

    /**
     * Set whether the busy fraction metric should be enabled. Default false.
     * <p>
     * Note: this metric is currently only compatible with non-concurrent task scheduler implementations. At a future
     * time this metric may be updated to work with concurrent scheduler implementations.
     *
     * @param enabled true if the busy fraction metric should be enabled, false otherwise
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withBusyFractionMetricsEnabled(boolean enabled);

    /**
     * Provide a custom thread pool for this task scheduler. If none is provided then the common fork join pool will be
     * used.
     *
     * @param pool the thread pool
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withPool(@NonNull ForkJoinPool pool);

    /**
     * Provide a custom uncaught exception handler for this task scheduler. If none is provided then the default
     * uncaught exception handler will be used. The default handler will write a message to the log.
     * <p>The order in which the {@code UncaughtExceptionHandler} is executed is undefined, the only guarantee is that it will
     * happen after the task that throws the exception.</p>
     *
     * @param uncaughtExceptionHandler the uncaught exception handler
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withUncaughtExceptionHandler(@NonNull UncaughtExceptionHandler uncaughtExceptionHandler);

    /**
     * Provide a hyperlink to documentation for this task scheduler. If none is provided then no hyperlink will be
     * generated. Used only for the automatically generated wiring diagram.
     *
     * @param hyperlink the hyperlink to the documentation for this task scheduler
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withHyperlink(@Nullable String hyperlink);

    /**
     * Provide a function to count weight of a data object for component health monitoring.
     * @param dataCounter the
     * @return this
     */
    @NonNull
    TaskSchedulerBuilder<OUT> withDataCounter(@NonNull ToLongFunction<Object> dataCounter);

    /**
     * Build the task scheduler.
     *
     * @return the task scheduler
     */
    @NonNull
    TaskScheduler<OUT> build();
}
