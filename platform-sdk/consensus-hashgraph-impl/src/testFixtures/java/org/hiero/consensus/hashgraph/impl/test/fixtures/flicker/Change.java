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
}
