// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.standard;

import static com.swirlds.component.framework.model.diagram.HyperlinkBuilder.platformCommonHyperlink;

import com.swirlds.base.time.Time;
import com.swirlds.common.utility.InstantUtils;
import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.UncaughtExceptionHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A scheduler that produces heartbeats at a specified rate.
 */
public abstract class AbstractHeartbeatScheduler {
    /** Name used by the heartbeat task scheduler */
    public static final String HEARTBEAT_SCHEDULER_NAME = "Heartbeat";

    private final TraceableWiringModel model;
    protected final Time time;
    protected final List<HeartbeatTask> tasks = new ArrayList<>();
    protected boolean started;

    /**
     * Constructor.
     *
     * @param model the wiring model containing this heartbeat scheduler
     * @param time  provides wall clock time
     */
    public AbstractHeartbeatScheduler(@NonNull final TraceableWiringModel model, @NonNull final Time time) {
        this.model = Objects.requireNonNull(model);
        this.time = Objects.requireNonNull(time);
        model.registerVertex(
                HEARTBEAT_SCHEDULER_NAME,
                TaskSchedulerType.SEQUENTIAL,
                platformCommonHyperlink(AbstractHeartbeatScheduler.class),
                false);
    }

    /**
     * Build a wire that produces an instant (reflecting current time) at the specified rate. Note that the exact rate
     * of heartbeats may vary. This is a best effort algorithm, and actual rates may vary depending on a variety of
     * factors.
     *
     * @param period           the period of the heartbeat. For example, setting a period of 100ms will cause the
     *                         heartbeat to be sent at 10 hertz. Note that time is measured at microsecond precision,
     *                         and so periods less than 1us are not supported.
     * @param exceptionHandler the handler for uncaught exceptions thrown by the heartbeat task
     * @return the output wire
     * @throws IllegalStateException if start has already been called
     */
    @NonNull
    public OutputWire<Instant> buildHeartbeatWire(
            @NonNull final Duration period, @NonNull final UncaughtExceptionHandler exceptionHandler) {
        if (started) {
            throw new IllegalStateException("Cannot create heartbeat wires after the heartbeat has started");
        }

        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("Period must be positive");
        }

        if (period.toNanos() <= 0 || period.toNanos() % InstantUtils.NANOS_IN_MICRO > 0) {
            throw new IllegalArgumentException("Period granularity of less than a 1us is not supported");
        }

        final HeartbeatTask task = new HeartbeatTask(model, HEARTBEAT_SCHEDULER_NAME, time, period, exceptionHandler);
        tasks.add(task);

        return task.getOutputWire();
    }

    /**
     * Start the heartbeats.
     */
    public abstract void start();

    /**
     * Stop the heartbeats.
     */
    public abstract void stop();
}
