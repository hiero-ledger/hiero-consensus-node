// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.Hash;

/**
 * One thing the consensus algorithm did to an event, reported by {@link RecordingEventImpl} at the moment it happened.
 * <p>
 * Events are keyed by content hash rather than by {@code EventImpl}: {@code EventImpl#hashCode} includes the mutable
 * {@code roundReceived}, so an {@code EventImpl} cannot be used as a map key across a run. The name is carried
 * alongside for readable assertions and failure messages; it is local to one hand-built graph and means nothing outside
 * it.
 * <p>
 * A change records a value <i>as it stood at emission</i>. Nothing here can be read back off the event afterwards —
 * {@code ConsensusImpl#recalculateAndVote} clears and recomputes every non-terminal event on each decided round — so
 * whatever an assertion needs has to be copied in when the change fires.
 * <p>
 * <b>Records are self-contained, deliberately, and some fields are redundant as a result.</b>
 * {@link MetadataCleared#wasWitness()} can be derived by scanning back for the most recent {@link WitnessFound} since
 * that event's last clear; {@link RoundCreatedSet#from()} is just the previous {@link RoundCreatedSet#to()} for the
 * same event. Both are carried anyway.
 * <p>
 * The reason is that there are two kinds of sink. {@link ConsensusTraceLog} accumulates, so it could scan backwards —
 * but the Falcon-side filter the design calls for evaluates each change and discards it, keeping no history at all. A
 * predicate that has to work against both can only read what the record in front of it carries. So do not remove a
 * field on the grounds that the log makes it recoverable: it is recoverable in one sink and not the other.
 */
public sealed interface Change {

    /** Position in the global order of changes, starting at 0. */
    long seq();

    /** The hash of the event the change happened to. */
    @NonNull
    Hash event();

    /** The name the test gave that event. */
    @NonNull
    String name();

    /**
     * An event was marked a witness.
     * <p>
     * There is no flag value here, because the flag carries no information. {@code isWitness} defaults to false and
     * only ever becomes true at a genuine discovery — {@code ConsensusImpl#calculateAndVote} finding a witness, or
     * {@code InitJudges#judgeFound} restoring one from a snapshot. The single place it is set back to false is
     * {@code EventImpl#clearJudgeFlags}, which is not a statement about witness-ness at all: it is one step of
     * {@code clearMetadata} wiping the event, and belongs to a {@code MetadataCleared} change rather than to this one.
     *
     * @param seq          position in the global order of changes
     * @param event        the content hash of the event
     * @param name         the name the test gave the event
     * @param roundCreated the event's round at the moment it was marked
     */
    record WitnessFound(
            long seq, @NonNull Hash event, @NonNull String name, long roundCreated) implements Change {}

    /**
     * An event's {@code roundCreated} was set.
     * <p>
     * Both the previous and the new value are carried. Beyond the self-containment rule above, the transition is the
     * thing properties are actually about: "a roster change promoted a non-judge" is a statement about an event's
     * round moving, and carrying {@code from} makes it a one-line predicate instead of a search.
     * <p>
     * This is the highest-volume record even so. {@code recalculateAndVote} clears and recomputes the round of every
     * non-terminal event on each decided round, so each decision produces two of these per surviving event. A write
     * that does not change the value emits nothing — otherwise {@code ConsensusImpl#addEvent}'s unconditional reset to
     * {@code ROUND_UNDEFINED} would add an empty record per add — but an assertion about one event's round history
     * still has to be scoped to a window rather than run over the whole log.
     *
     * @param seq   position in the global order of changes
     * @param event the content hash of the event
     * @param name  the name the test gave the event
     * @param from  the round the event was at before the call
     * @param to    the round it was set to
     */
    record RoundCreatedSet(
            long seq, @NonNull Hash event, @NonNull String name, long from, long to) implements Change {}

    /**
     * An event's consensus metadata was about to be wiped by {@code clearMetadata}.
     * <p>
     * Emitted <i>before</i> the call, because it carries values that are about to be lost. That is the opposite of
     * {@link WitnessFound}, and the difference is not stylistic: a change reporting a value being established can read
     * it afterwards, a change reporting one being destroyed cannot.
     * <p>
     * The absence of this record is as meaningful as its presence. {@code recalculateAndVote} exempts a judge of the
     * just-decided round whose parents are all at {@code ROUND_NEGATIVE_INFINITY}, and an exempted judge emits nothing
     * — correctly, since nothing was destroyed. That absence is the signature the SCN-001 carve-out is diagnosed by.
     * <p>
     * Note what cannot be captured: {@code clearJudgeFlags} sets {@code isJudge} with a direct field write rather than
     * through a setter, so judge-ness being cleared is invisible to the recorder. Infer it from this record.
     *
     * @param seq          position in the global order of changes
     * @param event        the content hash of the event
     * @param name         the name the test gave the event
     * @param roundCreated the round the event was at, before the wipe
     * @param wasWitness   whether it was a witness, before the wipe
     * @param wasJudge     whether it was a judge, before the wipe
     */
    record MetadataCleared(
            long seq,
            @NonNull Hash event,
            @NonNull String name,
            long roundCreated,
            boolean wasWitness,
            boolean wasJudge)
            implements Change {}
}
