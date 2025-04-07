// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.internal.deterministic.DeterministicHeartbeatScheduler;
import com.swirlds.component.framework.model.internal.deterministic.DeterministicTaskSchedulerBuilder;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerBuilder;
import com.swirlds.component.framework.wires.output.NoOpOutputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A deterministic implementation of a wiring model. Suitable for testing, not intended for production use cases.
 */
public class DeterministicWiringModel extends TraceableWiringModel {

    /**
     * Metrics instance used to report status.
     */
    private final Metrics metrics;
    /**
     * Work that we will perform in the current cycle.
     */
    private List<Runnable> currentCycleWork = new ArrayList<>();

    /**
     * Work that we will perform in the next cycle.
     */
    private List<Runnable> nextCycleWork = new ArrayList<>();

    private final DeterministicHeartbeatScheduler heartbeatScheduler;

    /**
     * Constructor.
     *
     * @param metrics the metrics
     * @param time the time
     */
    DeterministicWiringModel(@NonNull final Metrics metrics, @NonNull final Time time) {
        super(false);
        this.metrics = Objects.requireNonNull(metrics);
        this.heartbeatScheduler = new DeterministicHeartbeatScheduler(this, time, "heartbeat");
    }

    /**
     * Advance time. Amount of time that advances depends on how {@link com.swirlds.base.time.Time Time} has been
     * advanced.
     */
    public void tick() {
        for (final Runnable work : currentCycleWork) {
            work.run();
        }

        // Note: heartbeats are handled at their destinations during the next cycle.
        heartbeatScheduler.tick();

        currentCycleWork = nextCycleWork;
        nextCycleWork = new ArrayList<>();
    }

    /**
     * Submit a unit of work to be performed.
     *
     * @param work the work to be performed
     */
    private void submitWork(@NonNull final Runnable work) {
        // Work is never handled in the same cycle as when it is submitted.
        nextCycleWork.add(work);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public <O> TaskSchedulerBuilder<O> schedulerBuilder(@NonNull final String name) {
        return new DeterministicTaskSchedulerBuilder<>(metrics, this, name, this::submitWork);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OutputWire<Instant> buildHeartbeatWire(@NonNull final Duration period) {
        return heartbeatScheduler.buildHeartbeatWire(period);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OutputWire<Duration> getHealthMonitorWire() {
        return new NoOpOutputWire<>(this, "HealthMonitor");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Duration getUnhealthyDuration() {
        return Duration.ZERO;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OutputWire<Instant> buildHeartbeatWire(final double frequency) {
        return heartbeatScheduler.buildHeartbeatWire(frequency);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        throwIfStarted();
        markAsStarted();
        heartbeatScheduler.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        throwIfNotStarted();
        heartbeatScheduler.stop();
    }
}
