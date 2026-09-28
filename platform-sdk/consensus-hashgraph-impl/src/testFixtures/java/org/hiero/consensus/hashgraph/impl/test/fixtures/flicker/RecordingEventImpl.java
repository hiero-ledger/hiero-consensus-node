// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.Change.WitnessFound;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * An {@link EventImpl} that reports its own changes as the algorithm makes them.
 * <p>
 * Reading an event's intermediate state after a run does not work: {@code ConsensusImpl#recalculateAndVote} clears and
 * recomputes every non-terminal event on each decided round, so a value can be created and destroyed entirely inside
 * one {@code addEvent} call. Overriding the mutators puts the observation point inside that call instead of after it.
 * <p>
 * Two properties make the subclass safe, both checked against the code:
 * <ul>
 *     <li>{@link EventImpl}'s constructor sets {@code mark} directly and calls no overridable mutator, so there is no
 *         "the override runs before the subclass is initialized" hazard.</li>
 *     <li>{@code ConsensusImpl} sits in a different package, so it reaches {@code EventImpl} only through public
 *         methods — which virtual dispatch intercepts.</li>
 * </ul>
 * <p>
 * Only {@code setWitness} is overridden. The rest of the curated set is added when a test needs it; each one has to
 * decide for itself whether to emit before or after {@code super}, which is not a uniform choice. Here, after is
 * correct and load-bearing: {@code calculateAndVote} calls {@code round(event)} — which assigns {@code roundCreated} —
 * before it calls {@code setWitness(true)}, so the round is already right when the override fires. A destructive
 * mutator such as {@code clearMetadata} would have to emit <i>before</i> {@code super}, because it carries values that
 * are about to be lost.
 */
public class RecordingEventImpl extends EventImpl {

    private final String name;
    private final ChangeSink sink;

    /**
     * Constructor.
     *
     * @param platformEvent the event being wrapped
     * @param allParents    pointers to all parent events
     * @param name          the name the test knows this event by
     * @param sink          where changes are reported
     */
    public RecordingEventImpl(
            @NonNull final PlatformEvent platformEvent,
            @NonNull final List<EventImpl> allParents,
            @NonNull final String name,
            @NonNull final ChangeSink sink) {
        super(platformEvent, allParents);
        this.name = Objects.requireNonNull(name);
        this.sink = Objects.requireNonNull(sink);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Emits after {@code super}, snapshotting {@code roundCreated} as it stands at this moment. It cannot be read back
     * later — {@code recalculateAndVote} will have cleared and recomputed it — so anything an assertion needs to bucket
     * on has to be copied into the change when it fires.
     * <p>
     * Only the promotion is recorded. {@code isWitness} defaults to false, so {@code setWitness(true)} is the change
     * worth knowing about; the one call that passes false is {@code EventImpl#clearJudgeFlags}, reached only from
     * {@code clearMetadata}, and recording that here would file a step of wiping the event under a heading about
     * witness-ness. The clear is {@code MetadataCleared}'s to report.
     */
    @Override
    public void setWitness(final boolean witness) {
        super.setWitness(witness);
        if (witness) {
            final long roundCreated = getRoundCreated();
            sink.accept(seq -> new WitnessFound(seq, getBaseHash(), name, roundCreated));
        }
    }
}
