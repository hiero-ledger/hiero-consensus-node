// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model;

import com.swirlds.base.state.Startable;
import com.swirlds.base.state.Stoppable;
import com.swirlds.component.framework.model.diagram.ModelEdgeSubstitution;
import com.swirlds.component.framework.model.diagram.ModelGroup;
import com.swirlds.component.framework.model.diagram.ModelManualLink;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerBuilder;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A wiring model is a collection of task schedulers and the wires connecting them. It can be used to analyze the wiring
 * of a system and to generate diagrams.
 */
public interface WiringModel extends Startable, Stoppable {

    /**
     * Get a new task scheduler builder.
     *
     * @param name the name of the task scheduler. Used for metrics and debugging. Must be unique. Must only contain
     *             alphanumeric characters and underscores.
     * @param <O>  the data type of the scheduler's primary output wire
     * @return a new task scheduler builder
     */
    @NonNull
    <O> TaskSchedulerBuilder<O> schedulerBuilder(@NonNull final String name);

    /**
     * Check to see if there is cyclic backpressure in the wiring model. Cyclical back pressure can lead to deadlocks,
     * and so it should be avoided at all costs.
     *
     * <p>
     * If this method finds cyclical backpressure, it will log a message that will fail standard platform tests.
     *
     * @return true if there is cyclical backpressure, false otherwise
     */
    boolean checkForCyclicalBackpressure();

    /**
     * Task schedulers using the {@link TaskSchedulerType#DIRECT} strategy have very strict rules about how data can be
     * added to input wires. This method checks to see if these rules are being followed.
     *
     * <p>
     * If this method finds illegal direct scheduler usage, it will log a message that will fail standard platform
     * tests.
     *
     * @return true if there is illegal direct scheduler usage, false otherwise
     */
    boolean checkForIllegalDirectSchedulerUsage();

    /**
     * Check to see if there are any input wires that are unbound.
     *
     * <p>
     * If this method detects unbound input wires in the model, it will log a message that will fail standard platform
     * tests.
     *
     * @return true if there are unbound input wires, false otherwise
     */
    boolean checkForUnboundInputWires();

    /**
     * Generate a mermaid style wiring diagram.
     *
     * @param groups        optional groupings of vertices
     * @param substitutions edges to substitute
     * @param manualLinks   manual links to add to the diagram
     * @param moreMystery   if enabled then use a generic label for all input from mystery sources. This removes
     *                      information about mystery edges, but allows the diagram to be easier to groc. Turn this off
     *                      when attempting to debug mystery edges.
     * @return a mermaid style wiring diagram
     */
    @NonNull
    String generateWiringDiagram(
            @NonNull List<ModelGroup> groups,
            @NonNull List<ModelEdgeSubstitution> substitutions,
            @NonNull List<ModelManualLink> manualLinks,
            boolean moreMystery);

    /**
     * Build a wire that produces an instant (reflecting current time) at the specified rate. Note that the exact rate
     * of heartbeats may vary. This is a best effort algorithm, and actual rates may vary depending on a variety of
     * factors.
     *
     * @param period the period of the heartbeat. For example, setting a period of 100ms will cause the heartbeat to be
     *               sent at 10 hertz. Note that time is measured at millisecond precision, and so periods less than 1ms
     *               are not supported.
     * @return the output wire
     * @throws IllegalStateException if start() has already been called
     */
    @NonNull
    OutputWire<Instant> buildHeartbeatWire(@NonNull final Duration period);

    /**
     * Get the output of the wiring model's health monitor. The output of this wire is the length of time that any
     * particular scheduler has been in an unhealthy state, or {@link Duration#ZERO} if all schedulers are currently
     * healthy.
     *
     * @return the output wire
     */
    @NonNull
    OutputWire<Duration> getHealthMonitorWire();

    /**
     * Get the duration that any particular scheduler has been concurrently unhealthy. This getter is intended for use
     * by things outside of the wiring framework. For use within the framework, the proper way to access this value is
     * via the wire returned by {@link #getHealthMonitorWire()}.
     *
     * @return the duration that any particular scheduler has been concurrently unhealthy, or {@link Duration#ZERO} if
     * no scheduler is currently unhealthy
     */
    @NonNull
    Duration getUnhealthyDuration();

    /**
     * Build a wire that produces an instant (reflecting current time) at the specified rate. Note that the exact rate
     * of heartbeats may vary. This is a best effort algorithm, and actual rates may vary depending on a variety of
     * factors.
     *
     * @param frequency the frequency of the heartbeat in hertz. Note that time is measured at millisecond precision,
     *                  and so frequencies greater than 1000hz are not supported.
     * @return the output wire
     */
    @NonNull
    OutputWire<Instant> buildHeartbeatWire(final double frequency);

    /**
     * Start everything in the model that needs to be started. Performs static analysis of the wiring topology and
     * writes errors to the logs if problems are detected.
     */
    @Override
    void start();

    /**
     * Stops everything in the model that needs to be stopped.
     */
    @Override
    void stop();
}
