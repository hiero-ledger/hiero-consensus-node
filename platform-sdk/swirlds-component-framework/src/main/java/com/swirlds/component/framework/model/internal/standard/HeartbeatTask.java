// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.standard;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.UncaughtExceptionHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.TimerTask;

/**
 * A task that produces a heartbeat at a specified rate.
 */
public class HeartbeatTask extends TimerTask {

    private final Time time;
    private final Duration period;
    private final StandardOutputWire<Instant> outputWire;

    /**
     * Constructor.
     *
     * @param model            the wiring model that this heartbeat is for
     * @param name             the name of the output wire
     * @param time             provides wall clock time
     * @param period           the period of the heartbeat
     * @param exceptionHandler the handler for uncaught exceptions thrown by the heartbeat task
     */
    public HeartbeatTask(
            @NonNull final TraceableWiringModel model,
            @NonNull final String name,
            @NonNull final Time time,
            @NonNull final Duration period,
            @NonNull final UncaughtExceptionHandler exceptionHandler) {
        this.time = Objects.requireNonNull(time);
        this.period = Objects.requireNonNull(period);
        Objects.requireNonNull(name);

        this.outputWire = new StandardOutputWire<>(model, name, exceptionHandler);
    }

    /**
     * Get the period of the heartbeat.
     *
     * @return the period of the heartbeat
     */
    @NonNull
    public Duration getPeriod() {
        return period;
    }

    /**
     * Get the output wire. Produces an Instant with the current time at the specified rate.
     *
     * @return the output wire
     */
    @NonNull
    public OutputWire<Instant> getOutputWire() {
        return outputWire;
    }

    /**
     * Produce a single heartbeat.
     */
    @Override
    public void run() {
        outputWire.forward(time.now());
    }
}
