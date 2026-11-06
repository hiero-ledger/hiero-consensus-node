// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon.recording;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * One input or output of a recorded node, at the simulated time it happened.
 *
 * <p>The events of the steps are copies, as they were at that time; the simulation keeps changing its own objects. An
 * event from another node is copied as the node would receive it through gossip, with the origin
 * {@link org.hiero.consensus.model.event.EventOrigin#GOSSIP GOSSIP}; an event of the node itself as its event creator
 * built it, with the origin {@link org.hiero.consensus.model.event.EventOrigin#RUNTIME RUNTIME}. Each copy has the
 * state its event has at that point in production:
 * <ul>
 *   <li>{@link Received}: as gossip delivers it, not hashed, with the sender and the time received;</li>
 *   <li>{@link CreationAttempt}: as the event creator hands it over, hashed, with the time received;</li>
 *   <li>{@link Released}: as the orphan buffer hands it on, hashed, with the time received, the sender of an event
 *       from another node, the non-deterministic generation, and the sequence number.</li>
 * </ul>
 *
 * <p>Each copy also has the times that only metrics read:
 * <ul>
 *   <li>the time its last pipeline stage ended
 *       ({@link org.hiero.consensus.model.event.PlatformEvent#getLastStageTime() getLastStageTime()}): the time
 *       received, the time the event creator took the transactions from the pool, and the time of the release;</li>
 *   <li>for an event of the node itself, the times its transactions were received by the transaction pool
 *       ({@link org.hiero.consensus.model.event.PlatformEvent#getTransactionReceivedTimes()
 *       getTransactionReceivedTimes()}).</li>
 * </ul>
 */
public sealed interface Step {

    /**
     * Returns the simulated time of this step.
     *
     * @return the time
     */
    @NonNull
    Instant time();

    /**
     * An event arrived from another node, before deduplication.
     *
     * @param time the time it arrived
     * @param sender the node that sent this copy
     * @param event the event
     */
    record Received(
            @NonNull Instant time,
            @NonNull NodeId sender,
            @NonNull PlatformEvent event) implements Step {}

    /**
     * An event left the orphan buffer and reached the event creator: one of another node, or one the node created.
     * The orphan buffer passes the same events in the same order to the consensus engine.
     *
     * @param time the time it was released
     * @param event the event
     */
    record Released(@NonNull Instant time, @NonNull PlatformEvent event) implements Step {}

    /**
     * The event creator was asked to create an event.
     *
     * @param time the time of the attempt
     * @param created the event it created, or {@code null} if it created none
     */
    record CreationAttempt(@NonNull Instant time, @Nullable PlatformEvent created) implements Step {}

    /**
     * The event creator got a new event window.
     *
     * @param time the time it got it
     * @param window the event window
     */
    record NewEventWindow(@NonNull Instant time, @NonNull EventWindow window) implements Step {}

    /**
     * The event creator got a new platform status.
     *
     * @param time the time it got it
     * @param status the platform status
     */
    record PlatformStatusChange(
            @NonNull Instant time, @NonNull PlatformStatus status) implements Step {}
}
