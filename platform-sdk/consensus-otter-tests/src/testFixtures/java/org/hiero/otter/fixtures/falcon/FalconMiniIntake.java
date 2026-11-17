// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.sequence.map.SequenceMap;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;

/**
 * The intake of a mini node, in place of a {@code StandardEventDeduplicator} followed by a {@code DefaultOrphanBuffer}.
 *
 * <p>It releases what those two release: no ancient events, no duplicates, and every event only after its non-ancient
 * parents. The events leave in the same order as they leave the orphan buffer and get the same sequence numbers,
 * because a node passes events on to its peers in that order. The orphans waiting for a missing parent are therefore
 * kept in the same kind of map as in the orphan buffer, built the same way and changed in the same order: its
 * iteration order decides the order of the events released when the event window moves.
 *
 * <p>It keeps one map of the events it has rather than one in each component, plus the events by birth round, so that
 * a window shift only visits the events that became ancient. It leaves out what the event creator does not use: the
 * non-deterministic generation, metrics and the intake event counter. Unlike the deduplicator, it tells events apart
 * by descriptor only, so a second event with the same descriptor but another signature is dropped.
 *
 * <p>This class is not thread-safe. The lists it returns are reused and only valid until the next call.
 */
@SuppressWarnings("ForLoopReplaceableByForEach")
final class FalconMiniIntake {

    /** The value in {@link #knownEvents} of an event that was released without waiting for a parent. */
    private static final Orphan RELEASED = new Orphan();

    /** The sequence number of the first event released, the same as in the orphan buffer. */
    private static final long FIRST_SEQUENCE_NUMBER = 1;

    /** The initial capacity of {@link #missingParents}, the same as in the orphan buffer. */
    private static final int INITIAL_CAPACITY = 1024;

    /** Avoids the creation of a lambda for every call of {@link SequenceMap#computeIfAbsent}. */
    private static final Function<EventDescriptorWrapper, List<Orphan>> NEW_ORPHAN_LIST = _ -> new ArrayList<>(2);

    /**
     * Every non-ancient event this node has. The value is {@link #RELEASED}, or the {@link Orphan} of an event that had
     * to wait for a parent. An orphan stays in the map when it is released, which it is once it misses no parent.
     */
    private final Map<EventDescriptorWrapper, Orphan> knownEvents = new HashMap<>();

    /**
     * The descriptors in {@link #knownEvents} by birth round: the list at index {@code i} holds those of birth round
     * {@link #lowestKnownBirthRound} {@code + i}. The lists of rounds that become ancient are emptied and reused for
     * the rounds after the last one.
     */
    private final List<List<EventDescriptorWrapper>> knownEventsByBirthRound = new ArrayList<>();

    /** The birth round of the first list in {@link #knownEventsByBirthRound}. */
    private long lowestKnownBirthRound = ConsensusConstants.ROUND_FIRST;

    /** The orphans waiting for each missing parent, in the order they arrived. */
    private final SequenceMap<EventDescriptorWrapper, List<Orphan>> missingParents =
            new StandardSequenceMap<>(0, INITIAL_CAPACITY, true, EventDescriptorWrapper::birthRound);

    /** The current event window. */
    @NonNull
    private EventWindow eventWindow = EventWindow.getGenesisEventWindow();

    /** The sequence number of the next event released. */
    private long nextSequenceNumber = FIRST_SEQUENCE_NUMBER;

    /** The events released by the current call. Reused across calls. */
    private final List<PlatformEvent> releasedEvents = new ArrayList<>();

    /** The events the current release still has to visit. Reused across calls. */
    private final ArrayDeque<PlatformEvent> releaseStack = new ArrayDeque<>();

    /** The orphans of the parents that became ancient in the current window shift. Reused across calls. */
    private final List<List<Orphan>> orphansOfAncientParents = new ArrayList<>();

    /**
     * An event that arrived before some of its parents. {@link FalconMiniIntake#RELEASED} is a special instance that stands for
     * every event released without waiting: like an orphan that has been released, it misses no parent.
     */
    private static final class Orphan {

        /** The event, or {@code null} for {@link FalconMiniIntake#RELEASED}. */
        private final PlatformEvent event;

        /** The number of its parents that are neither released nor ancient. */
        private int missingParentCount;

        /** Creates {@link FalconMiniIntake#RELEASED}. */
        private Orphan() {
            this.event = null;
        }

        private Orphan(@NonNull final PlatformEvent event) {
            this.event = event;
        }

        /**
         * Gets the event. Only called for orphans that wait for a parent, which {@link FalconMiniIntake#RELEASED} never does.
         *
         * @return the event
         */
        @NonNull
        private PlatformEvent event() {
            assert event != null : "RELEASED has no event";
            return event;
        }
    }

    /**
     * Takes in an event.
     *
     * @param event the event
     * @return the events released by it, in order; the list is only valid until the next call
     */
    @NonNull
    List<PlatformEvent> handleEvent(@NonNull final PlatformEvent event) {
        releasedEvents.clear();
        if (eventWindow.isAncient(event)) {
            return releasedEvents;
        }
        final EventDescriptorWrapper descriptor = event.getDescriptor();
        if (knownEvents.putIfAbsent(descriptor, RELEASED) != null) {
            // a duplicate of an event that has been released or is waiting for its parents
            return releasedEvents;
        }
        addToBirthRound(descriptor);

        Orphan orphan = null;
        final List<EventDescriptorWrapper> parents = event.getAllParents();
        for (int i = 0, n = parents.size(); i < n; i++) {
            final EventDescriptorWrapper parent = parents.get(i);
            if (isMissing(parent)) {
                if (orphan == null) {
                    orphan = new Orphan(event);
                    knownEvents.put(descriptor, orphan);
                }
                orphan.missingParentCount++;
                missingParents.computeIfAbsent(parent, NEW_ORPHAN_LIST).add(orphan);
            }
        }
        if (orphan == null) {
            release(event);
        }
        return releasedEvents;
    }

    /**
     * Sets the event window. Forgets the events that became ancient, and releases the orphans that only waited for
     * parents that became ancient.
     *
     * @param eventWindow the new event window
     * @return the events released by it, in order; the list is only valid until the next call
     */
    @NonNull
    List<PlatformEvent> setEventWindow(@NonNull final EventWindow eventWindow) {
        releasedEvents.clear();
        this.eventWindow = eventWindow;

        final long ancientThreshold = eventWindow.ancientThreshold();
        final long ancientRounds = Math.min(ancientThreshold - lowestKnownBirthRound, knownEventsByBirthRound.size());
        for (long round = 0; round < ancientRounds; round++) {
            final List<EventDescriptorWrapper> descriptors = knownEventsByBirthRound.removeFirst();
            for (int i = 0, n = descriptors.size(); i < n; i++) {
                knownEvents.remove(descriptors.get(i));
            }
            descriptors.clear();
            knownEventsByBirthRound.addLast(descriptors);
        }
        lowestKnownBirthRound = Math.max(lowestKnownBirthRound, ancientThreshold);

        // As in the orphan buffer, the orphans are collected during the shift and released after it
        missingParents.shiftWindow(ancientThreshold, (_, orphans) -> orphansOfAncientParents.add(orphans));
        for (int i = 0, n = orphansOfAncientParents.size(); i < n; i++) {
            final List<Orphan> orphans = orphansOfAncientParents.get(i);
            for (int j = 0, m = orphans.size(); j < m; j++) {
                final Orphan orphan = orphans.get(j);
                if (--orphan.missingParentCount == 0) {
                    release(orphan.event());
                }
            }
        }
        orphansOfAncientParents.clear();
        return releasedEvents;
    }

    /**
     * Gets the number of events this intake has. Exposed for testing.
     *
     * @return the number of non-ancient events
     */
    int getKnownEventCount() {
        return knownEvents.size();
    }

    /**
     * Adds a descriptor that was just added to {@link #knownEvents} to the list of its birth round.
     *
     * @param descriptor the descriptor
     */
    private void addToBirthRound(@NonNull final EventDescriptorWrapper descriptor) {
        // a non-ancient event never has a birth round below lowestKnownBirthRound
        final int index = (int) (descriptor.birthRound() - lowestKnownBirthRound);
        while (knownEventsByBirthRound.size() <= index) {
            knownEventsByBirthRound.add(new ArrayList<>());
        }
        knownEventsByBirthRound.get(index).add(descriptor);
    }

    /**
     * Checks whether a parent is missing, that is, neither ancient nor released.
     *
     * @param parent the parent
     * @return {@code true} if the parent is missing
     */
    private boolean isMissing(@NonNull final EventDescriptorWrapper parent) {
        if (eventWindow.isAncient(parent)) {
            return false;
        }
        final Orphan knownEvent = knownEvents.get(parent);
        return knownEvent == null || knownEvent.missingParentCount > 0;
    }

    /**
     * Releases an event that misses no parent, and then every orphan that only waited for it, depth first, as the
     * orphan buffer does.
     *
     * @param event the event
     */
    private void release(@NonNull final PlatformEvent event) {
        releaseStack.push(event);
        while (!releaseStack.isEmpty()) {
            final PlatformEvent next = releaseStack.pop();
            if (eventWindow.isAncient(next)) {
                continue;
            }
            releasedEvents.add(next);
            next.setSequenceNumber(nextSequenceNumber++);

            // Removing an absent key leaves the map unchanged, so the lookup is skipped while no orphan waits
            if (missingParents.getSize() == 0) {
                continue;
            }
            final List<Orphan> children = missingParents.remove(next.getDescriptor());
            if (children == null) {
                continue;
            }
            for (int i = 0, n = children.size(); i < n; i++) {
                final Orphan child = children.get(i);
                if (--child.missingParentCount == 0) {
                    releaseStack.push(child.event());
                }
            }
        }
    }
}
