// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.otter.fixtures.util.CursoredLog;
import org.hiero.otter.fixtures.util.CursoredLog.Cursor;

/**
 * The events that have been submitted to the simulated network and are not expired for every node yet, each as a
 * {@link TrackedEvent}.
 *
 * <p>The events are kept twice: by descriptor in an {@link EventDeduplicator}, which recognizes repeated submissions
 * and finds the event of an instance in flight, and in submission order in a log, which is walked to send a node
 * everything again when it restarts, and to send the nodes of a link that comes up what they lack. Both are pruned by
 * the event window of the node that is furthest behind.
 *
 * <p>This class is not safe to use from more than one thread.
 */
final class TrackedEvents {

    /**
     * The initial capacity of the sequence number space for the log of events. This should match the number of
     * non-expired rounds according to the default configuration of the nodes in the network, but it does not have to be
     * exact because the log will expand as needed.
     */
    private static final int INITIAL_SEQUENCE_NUMBER_CAPACITY = 1000;

    /**
     * The initial capacity of the log of events. This should be large enough to hold all events that are not expired,
     * but it does not have to be exact because the log will expand as needed. Must be a power of 2.
     */
    private static final int INITIAL_EVENT_LOG_CAPACITY = 1 << 8;

    /**
     * Recognizes events that have already been submitted by a node, and keeps the tracked event of each.
     */
    private final EventDeduplicator deduplicator;

    /**
     * The tracked events, one entry for each event and signature, in the order they were first submitted. Nothing is
     * read from it while every link stays up; it is walked when a node restarts and when a link comes up.
     */
    private final CursoredLog<TrackedEvent> eventLog = new CursoredLog<>(
            ConsensusConstants.ROUND_FIRST,
            INITIAL_SEQUENCE_NUMBER_CAPACITY,
            INITIAL_EVENT_LOG_CAPACITY,
            trackedEvent -> trackedEvent.event().getBirthRound());

    /**
     * The highest birth round that has been pruned from {@link #eventLog}. Birth rounds must be pruned in strictly
     * increasing order, so this records how far the pruning has already progressed.
     */
    private long lastPrunedBirthRound = ConsensusConstants.ROUND_FIRST - 1;

    /**
     * Constructor.
     *
     * @param roster the roster of the network
     */
    TrackedEvents(@NonNull final RosterWrapper roster) {
        deduplicator = new EventDeduplicator(roster);
    }

    /**
     * Records the submission of an event by a node, see {@link EventDeduplicator#addIfUnique(PlatformEvent, int)}.
     * An event that is submitted for the first time is added to the log, unless it is too old to be tracked, in which
     * case the log would no longer accept it.
     *
     * @param event     the submitted event
     * @param submitter the rank of the node that submitted it
     * @return the tracked event, or {@code null} if the node has submitted the event before
     */
    @Nullable
    TrackedEvent addIfUnique(@NonNull final PlatformEvent event, final int submitter) {
        final TrackedEvent tracked = deduplicator.addIfUnique(event, submitter);
        if (tracked != null && event.getBirthRound() > lastPrunedBirthRound && tracked.markLogged()) {
            eventLog.add(tracked);
        }
        return tracked;
    }

    /**
     * Finds the tracked event of an instance of an event.
     *
     * @param event an instance of the event
     * @return the tracked event, or {@code null} if it is not tracked
     */
    @Nullable
    TrackedEvent find(@NonNull final PlatformEvent event) {
        return deduplicator.find(event);
    }

    /**
     * Creates a cursor over the tracked events, in the order they were first submitted.
     *
     * @return the cursor, positioned at the oldest tracked event
     */
    @NonNull
    Cursor<TrackedEvent> newCursor() {
        return eventLog.newCursor();
    }

    /**
     * Forgets the events that are expired for the node that is furthest behind, and so for the whole network.
     *
     * @param oldestEventWindow the event window of the node that is furthest behind
     */
    void applyOldestEventWindow(@NonNull final EventWindow oldestEventWindow) {
        deduplicator.setOldestEventWindow(oldestEventWindow);

        // An event is expired for a node when its birth round is strictly below that node's expired threshold,
        // so the highest birth round that is expired for all of them is one below the lowest threshold.
        final long pruneThroughBirthRound = oldestEventWindow.expiredThreshold() - 1;
        if (pruneThroughBirthRound > lastPrunedBirthRound) {
            eventLog.removeSequenceNumber(pruneThroughBirthRound);
            lastPrunedBirthRound = pruneThroughBirthRound;
        }
    }
}
