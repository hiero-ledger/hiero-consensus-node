// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon.recording;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.benchmark.tools.event.EventStreamGenerator;
import org.hiero.consensus.benchmark.tools.pipeline.TimedInput;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * One input or output of a recorded node, at the simulated time it happened.
 *
 * <p>The events of the steps are copies, as they were at that time; the simulation keeps changing its own objects. The
 * factories ({@code of}) make the copies when the step is recorded, and {@link #pristine()} makes a new one in the same
 * state for every pass of a replay. Each copy is sent through the gossip streams
 * ({@link EventStreamGenerator#receiveUnhashed(PlatformEvent)}), so that its lists and byte buffers are new, as in
 * production, where no code has used them yet. An event from another node has the origin {@link EventOrigin#GOSSIP}; an
 * event of the node itself is rebuilt as its event creator builds it, with the origin {@link EventOrigin#RUNTIME}. Each
 * copy has the state its event has at that point in production:
 * <ul>
 *   <li>{@link Received}: as gossip delivers it, not hashed, with the sender and the time received;</li>
 *   <li>{@link CreationAttempt}: as the event creator hands it over, hashed, with the time received;</li>
 *   <li>{@link Released}: as the orphan buffer hands it on, hashed, with the time received, the sender of an event
 *       from another node, the non-deterministic generation, and the sequence number.</li>
 * </ul>
 *
 * <p>Each copy also has the times that only metrics read:
 * <ul>
 *   <li>the time its last pipeline stage ended ({@link PlatformEvent#getLastStageTime()}): the time received, the
 *       time the event creator took the transactions from the pool, and the time of the release;</li>
 *   <li>for an event of the node itself, the times its transactions were received by the transaction pool
 *       ({@link PlatformEvent#getTransactionReceivedTimes()}).</li>
 * </ul>
 */
public sealed interface Step extends TimedInput {

    /**
     * Returns the simulated time of this step.
     *
     * @return the time
     */
    @Override
    @NonNull
    Instant time();

    /**
     * Returns this step with a new copy of its event, in the state described above, or this step if it has no event.
     *
     * @return a step that no pass has touched
     */
    @Override
    @NonNull
    Step pristine();

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
            @NonNull PlatformEvent event) implements Step {

        /**
         * Records an event that gossip delivered, with a copy of it. The simulated network delivers hashed events,
         * which saves hashing every copy; the copy is not hashed, as in production, which leaves the hashing to the
         * intake.
         *
         * @param time the time it arrived
         * @param delivered the event as gossip delivered it, with its sender and its time received
         * @return the step
         */
        @NonNull
        public static Received of(@NonNull final Instant time, @NonNull final PlatformEvent delivered) {
            return new Received(time, requireNonNull(delivered.getSenderId()), received(delivered));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public Received pristine() {
            return new Received(time, sender, received(event));
        }
    }

    /**
     * An event left the orphan buffer and reached the event creator: one of another node, or one the node created.
     * The orphan buffer passes the same events in the same order to the consensus engine.
     *
     * @param time the time it was released
     * @param event the event
     */
    record Released(@NonNull Instant time, @NonNull PlatformEvent event) implements Step {

        /**
         * Records an event that the orphan buffer released, with a copy of it.
         *
         * @param time the time it was released
         * @param event the event
         * @param origin {@link EventOrigin#RUNTIME} for an event of the node itself, otherwise
         * {@link EventOrigin#GOSSIP}
         * @return the step
         */
        @NonNull
        public static Released of(
                @NonNull final Instant time, @NonNull final PlatformEvent event, @NonNull final EventOrigin origin) {
            return new Released(time, copy(event, origin, time));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public Released pristine() {
            return new Released(time, copy(event, event.getOrigin(), event.getLastStageTime()));
        }
    }

    /**
     * The event creator was asked to create an event.
     *
     * @param time the time of the attempt
     * @param created the event it created, or {@code null} if it created none
     */
    record CreationAttempt(@NonNull Instant time, @Nullable PlatformEvent created) implements Step {

        /**
         * Records an attempt of the event creator, with a copy of the event it created.
         *
         * @param time the time of the attempt
         * @param created the event it created, or {@code null} if it created none
         * @return the step
         */
        @NonNull
        public static CreationAttempt of(@NonNull final Instant time, @Nullable final PlatformEvent created) {
            return new CreationAttempt(
                    time, created == null ? null : copy(created, EventOrigin.RUNTIME, created.getLastStageTime()));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public CreationAttempt pristine() {
            return created == null ? this : of(time, created);
        }
    }

    /**
     * The event creator got a new event window.
     *
     * @param time the time it got it
     * @param window the event window
     */
    record NewEventWindow(@NonNull Instant time, @NonNull EventWindow window) implements Step {

        /**
         * Returns this step: an event window cannot change.
         *
         * @return this step
         */
        @Override
        @NonNull
        public NewEventWindow pristine() {
            return this;
        }
    }

    /**
     * The event creator got a new platform status.
     *
     * @param time the time it got it
     * @param status the platform status
     */
    record PlatformStatusChange(
            @NonNull Instant time, @NonNull PlatformStatus status) implements Step {

        /**
         * Returns this step: a platform status cannot change.
         *
         * @return this step
         */
        @Override
        @NonNull
        public PlatformStatusChange pristine() {
            return this;
        }
    }

    /**
     * Copies an event as gossip delivers it: not hashed, with the sender and the time received, which is also the time
     * its first stage starts.
     */
    @NonNull
    private static PlatformEvent received(@NonNull final PlatformEvent event) {
        final PlatformEvent copy = EventStreamGenerator.receiveUnhashed(event);
        copy.setSenderId(requireNonNull(event.getSenderId()));
        copy.setTimeReceived(event.getTimeReceived());
        copy.setLastStageTime(event.getTimeReceived());
        return copy;
    }

    /**
     * Copies a hashed event with the given origin and last stage time, keeping its hash, time received, sender,
     * non-deterministic generation, sequence number, and the received times of its transactions.
     */
    @NonNull
    private static PlatformEvent copy(
            @NonNull final PlatformEvent event,
            @NonNull final EventOrigin origin,
            @NonNull final Instant lastStageTime) {
        final PlatformEvent parsed = EventStreamGenerator.receiveUnhashed(event);
        final PlatformEvent copy;
        if (origin == EventOrigin.GOSSIP) {
            copy = parsed;
        } else {
            final UnsignedEvent unsignedEvent = new UnsignedEvent(
                    parsed.getCreatorId(),
                    parsed.getAllParents(),
                    parsed.getBirthRound(),
                    parsed.getTimeCreated(),
                    parsed.getGossipEvent().transactions(),
                    parsed.getEventCore().coin(),
                    lastStageTime,
                    List.copyOf(event.getTransactionReceivedTimes()));
            copy = new PlatformEvent(unsignedEvent, parsed.getSignature(), origin);
        }
        copy.setHash(event.getHash());
        copy.setTimeReceived(event.getTimeReceived());
        copy.setLastStageTime(lastStageTime);
        if (event.getSenderId() != null) {
            copy.setSenderId(event.getSenderId());
        }
        copy.setNGen(event.getNGen());
        copy.setSequenceNumber(event.getSequenceNumber());
        return copy;
    }
}
