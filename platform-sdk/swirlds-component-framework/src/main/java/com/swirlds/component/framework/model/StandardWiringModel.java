// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.NO_OP;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.SEQUENTIAL;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.SEQUENTIAL_THREAD;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.diagram.HyperlinkBuilder;
import com.swirlds.component.framework.model.internal.monitor.HealthMonitor;
import com.swirlds.component.framework.model.internal.standard.AbstractHeartbeatScheduler;
import com.swirlds.component.framework.model.internal.standard.HeartbeatScheduler;
import com.swirlds.component.framework.model.internal.standard.JvmAnchor;
import com.swirlds.component.framework.schedulers.ExceptionHandlers;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerBuilder;
import com.swirlds.component.framework.schedulers.builders.internal.StandardTaskSchedulerBuilder;
import com.swirlds.component.framework.schedulers.internal.SequentialThreadTaskScheduler;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.Thread.UncaughtExceptionHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

/**
 * A standard implementation of a wiring model suitable for production use.
 */
public class StandardWiringModel extends TraceableWiringModel {

    private final Metrics metrics;
    private final Time time;

    /**
     * Schedules heartbeats. Not created unless needed.
     */
    private HeartbeatScheduler heartbeatScheduler = null;

    /**
     * The scheduler that the health monitor runs on.
     */
    private final TaskScheduler<Duration> healthMonitorScheduler;

    /**
     * The health monitor.
     */
    private HealthMonitor healthMonitor;

    /**
     * The input wire for the health monitor.
     */
    private final BindableInputWire<Instant, Duration> healthMonitorInputWire;

    /**
     * Thread schedulers need to have their threads started/stopped.
     */
    private final List<SequentialThreadTaskScheduler<?>> threadSchedulers = new ArrayList<>();

    /**
     * The default fork join pool, schedulers not explicitly assigned a pool will use this one.
     */
    private final ForkJoinPool defaultPool;

    /**
     * Used to prevent the JVM from prematurely exiting.
     */
    private final JvmAnchor anchor;

    /**
     * The amount of time that must pass before we start logging health information.
     */
    private final Duration healthLogThreshold;

    /**
     * The period at which we log health information.
     */
    private final Duration healthLogPeriod;

    /**
     * How long between two consecutive reports when the system is healthy.
     */
    private final Duration healthyReportThreshold;

    /**
     * The (optional) global {@link UncaughtExceptionHandler} for the wiring framework
     */
    private final UncaughtExceptionHandler taskSchedulerExceptionHandler;

    /**
     * Constructor.
     *
     * @param builder the builder for this model, contains all needed configuration
     */
    StandardWiringModel(@NonNull final WiringModelBuilder builder) {
        super(builder.isHardBackpressureEnabled());

        this.metrics = Objects.requireNonNull(builder.getMetrics());
        this.time = Objects.requireNonNull(builder.getTime());
        this.defaultPool = Objects.requireNonNull(builder.getDefaultPool());

        final TaskSchedulerBuilder<Duration> healthMonitorSchedulerBuilder = this.schedulerBuilder("HealthMonitor");
        healthMonitorSchedulerBuilder.withHyperlink(HyperlinkBuilder.platformCoreHyperlink(HealthMonitor.class));
        if (builder.isHealthMonitorEnabled()) {
            healthMonitorSchedulerBuilder
                    .withType(SEQUENTIAL)
                    .withUnhandledTaskMetricEnabled(true)
                    .withUnhandledTaskCapacity(builder.getHealthMonitorCapacity());
        } else {
            healthMonitorSchedulerBuilder.withType(NO_OP);
        }

        healthLogThreshold = builder.getHealthLogThreshold();
        healthLogPeriod = builder.getHealthLogPeriod();
        healthyReportThreshold = builder.getHealthyReportThreshold();
        healthMonitorScheduler = healthMonitorSchedulerBuilder.build();
        healthMonitorInputWire = healthMonitorScheduler.buildInputWire("check system health");
        buildHeartbeatWire(builder.getHealthMonitorPeriod()).solderTo(healthMonitorInputWire);

        if (builder.isJvmAnchorEnabled()) {
            anchor = new JvmAnchor();
        } else {
            anchor = null;
        }

        taskSchedulerExceptionHandler = builder.getTaskSchedulerExceptionHandler();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public final <O> TaskSchedulerBuilder<O> schedulerBuilder(@NonNull final String name) {
        throwIfStarted();
        final StandardTaskSchedulerBuilder<O> builder =
                new StandardTaskSchedulerBuilder<>(this.time, this.metrics, this, name, defaultPool);
        if (taskSchedulerExceptionHandler != null) {
            builder.withUncaughtExceptionHandler(taskSchedulerExceptionHandler);
        }
        return builder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<Instant> buildHeartbeatWire(@NonNull final Duration period) {
        throwIfStarted();
        return getHeartbeatScheduler().buildHeartbeatWire(period, getHeartbeatExceptionHandler());
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OutputWire<Duration> getHealthMonitorWire() {
        return healthMonitorScheduler.getOutputWire();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Duration getUnhealthyDuration() {
        throwIfNotStarted();
        return healthMonitor.getUnhealthyDuration();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<Instant> buildHeartbeatWire(final double frequency) {
        throwIfStarted();
        return getHeartbeatScheduler().buildHeartbeatWire(frequency, getHeartbeatExceptionHandler());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerScheduler(@NonNull final TaskScheduler<?> scheduler, @Nullable final String hyperlink) {
        super.registerScheduler(scheduler, hyperlink);
        if (scheduler.getType() == SEQUENTIAL_THREAD) {
            threadSchedulers.add((SequentialThreadTaskScheduler<?>) scheduler);
        }
    }

    /**
     * Get the uncaught exception handler for the heartbeat scheduler if it has been set, otherwise return a default
     *
     * @return the uncaught exception handler
     */
    @NonNull
    private UncaughtExceptionHandler getHeartbeatExceptionHandler() {
        return Optional.ofNullable(taskSchedulerExceptionHandler)
                .orElse(ExceptionHandlers.defaultExceptionHandler(AbstractHeartbeatScheduler.HEARTBEAT_SCHEDULER_NAME));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        throwIfStarted();

        if (anchor != null) {
            anchor.start();
        }

        healthMonitor = new HealthMonitor(
                metrics, time, schedulers, healthLogThreshold, healthLogPeriod, healthyReportThreshold);
        healthMonitorInputWire.bind(healthMonitor::checkSystemHealth);

        markAsStarted();

        // We don't have to do anything with the output of these sanity checks.
        // The methods below will log errors if they find problems.
        checkForCyclicalBackpressure();
        checkForIllegalDirectSchedulerUsage();
        checkForUnboundInputWires();

        if (heartbeatScheduler != null) {
            heartbeatScheduler.start();
        }

        for (final SequentialThreadTaskScheduler<?> threadScheduler : threadSchedulers) {
            threadScheduler.start();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        throwIfNotStarted();

        if (heartbeatScheduler != null) {
            heartbeatScheduler.stop();
        }

        for (final SequentialThreadTaskScheduler<?> threadScheduler : threadSchedulers) {
            threadScheduler.stop();
        }

        if (anchor != null) {
            anchor.stop();
        }
    }

    /**
     * Get the heartbeat scheduler, creating it if necessary.
     *
     * @return the heartbeat scheduler
     */
    @NonNull
    private HeartbeatScheduler getHeartbeatScheduler() {
        if (heartbeatScheduler == null) {
            heartbeatScheduler = new HeartbeatScheduler(this, time);
        }
        return heartbeatScheduler;
    }
}
