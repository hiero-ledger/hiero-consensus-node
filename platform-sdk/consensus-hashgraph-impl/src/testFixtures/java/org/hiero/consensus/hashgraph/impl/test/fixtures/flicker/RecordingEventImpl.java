// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.Change.MetadataCleared;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.Change.RoundCreatedSet;
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
 * Each override decides for itself whether to emit before or after {@code super}, and the choice is not uniform:
 * <ul>
 *     <li>{@code setWitness} and {@code setRoundCreated} emit <b>after</b>. They report a value being established,
 *         which can still be read once the call has returned.</li>
 *     <li>{@code clearMetadata} emits <b>before</b>. It reports values being destroyed, so reading them afterwards
 *         would report the wipe rather than what was wiped.</li>
 * </ul>
 * {@code setWitness}'s choice is also load-bearing for a second reason: {@code calculateAndVote} calls
 * {@code round(event)} — which assigns {@code roundCreated} — before it calls {@code setWitness(true)}, so the round
 * is already correct when the override fires.
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

    /**
     * {@inheritDoc}
     * <p>
     * Emits after {@code super}, carrying the transition rather than the resulting state: a property about a roster
     * change is a statement about an event's round moving, not about where it ended up.
     * <p>
     * A write that does not change the value emits nothing. {@code ConsensusImpl#addEvent} sets the round to
     * {@code ROUND_UNDEFINED} on every add, and for a freshly linked event it is already undefined, so without this
     * the log carries one empty {@code undef -> undef} record per event added. Dropping them here rather than in each
     * assertion keeps the noise out of the record that every future query has to filter.
     */
    @Override
    public void setRoundCreated(final long roundCreated) {
        final long from = getRoundCreated();
        super.setRoundCreated(roundCreated);
        if (from != roundCreated) {
            sink.accept(seq -> new RoundCreatedSet(seq, getBaseHash(), name, from, roundCreated));
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Emits <b>before</b> {@code super}, carrying the values that are about to be lost. Note that the nested
     * {@code setWitness(false)} inside {@code clearJudgeFlags} emits nothing of its own, so a wipe appears in the log
     * as exactly one record rather than as a cascade.
     */
    @Override
    public void clearMetadata() {
        final long roundCreated = getRoundCreated();
        final boolean wasWitness = isWitness();
        final boolean wasJudge = isJudge();
        sink.accept(seq -> new MetadataCleared(seq, getBaseHash(), name, roundCreated, wasWitness, wasJudge));
        super.clearMetadata();
    }
}
