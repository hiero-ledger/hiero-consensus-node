// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.SYNC_INFO;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.gossip.IntakeEventCounter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.Clearable;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * The primary purpose of the shadowgraph is to unlink events when it is safe to do so. In order to decide when it is
 * safe to unlink an event, it allows for batches of events (by ancient indicator) to be reserved.
 */
public class Shadowgraph implements Clearable {

    private static final Logger logger = LogManager.getLogger(Shadowgraph.class);

    /**
     * The ancient indicator indicating that nothing is currently reserved.
     */
    public static final int NO_RESERVATION = -1;

    /**
     * The shadowgraph represented in a map from has to shadow event.
     */
    private final HashMap<Hash, ShadowEvent> hashToShadowEvent;

    /**
     * Map from ancient indicator to all shadow events with that ancient indicator.
     */
    private final Map<Long /* ancient indicator */, Set<ShadowEvent>> indicatorToShadowEvent;

    /**
     * The set of all tips for the shadowgraph. A tip is an event with no self child (could have other children)
     */
    private final HashSet<ShadowEvent> tips;

    /**
     * The oldest ancient indicator that has not yet been expired
     */
    private long oldestUnexpiredIndicator;

    /**
     * The list of all currently reserved indicators and their number of reservations.
     */
    private final LinkedList<ShadowgraphReservation> reservationList;

    /**
     * Encapsulates metrics for the shadowgraph.
     */
    private final ShadowgraphMetrics metrics;

    /**
     * the number of nodes in the network, used for debugging
     */
    private final int numberOfNodes;

    /**
     * Describes the current ancient mode.
     */
    private final AncientMode ancientMode;

    /**
     * The most recent event window we know about.
     */
    private EventWindow eventWindow;

    /**
     * For each peer, track the number of events in the intake pipeline prior to the shadowgraph.
     */
    private final IntakeEventCounter intakeEventCounter;

    /**
     * Constructor.
     *
     * @param platformContext    the platform context
     * @param numberOfNodes      numberOfNodes
     * @param intakeEventCounter tracks events in the intake pipeline
     */
    public Shadowgraph(
            @NonNull final PlatformContext platformContext,
            @NonNull final int numberOfNodes,
            @NonNull final IntakeEventCounter intakeEventCounter) {

        ancientMode = platformContext
                .getConfiguration()
                .getConfigData(EventConfig.class)
                .getAncientMode();

        this.metrics = new ShadowgraphMetrics(platformContext);
        this.numberOfNodes = numberOfNodes;
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);
        tips = new HashSet<>();
        hashToShadowEvent = new HashMap<>();
        indicatorToShadowEvent = new HashMap<>();
        reservationList = new LinkedList<>();
    }

    /**
     * Define the starting event window for the shadowgraph
     *
     * @param eventWindow the starting event window
     */
    private void startWithEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = eventWindow;
        oldestUnexpiredIndicator = eventWindow.getExpiredThreshold();
        logger.info(
                STARTUP.getMarker(),
                "Shadowgraph starting from expiration threshold {}",
                eventWindow.getExpiredThreshold());
    }

    /**
     * Reset the shadowgraph manager to its constructed state.
     */
    public synchronized void clear() {
        eventWindow = null;
        oldestUnexpiredIndicator = ancientMode.getGenesisIndicator();
        disconnectShadowEvents();
        tips.clear();
        hashToShadowEvent.clear();
        indicatorToShadowEvent.clear();
        reservationList.clear();
    }

    /**
     * Disconnect all shadow events to help the garbage collector.
     */
    private void disconnectShadowEvents() {
        for (final ShadowEvent shadow : hashToShadowEvent.values()) {
            shadow.disconnect();
        }
    }

    /**
     * Increase the reservation count for the ancient indicator currently held by {@code expireBelow}. A reservation
     * prevents events that have an ancient indicator not less than the threshold from being unlinked.
     *
     * @return the reservation instance, must be closed when the reservation is no longer needed
     */
    @NonNull
    public synchronized ReservedEventWindow reserve() {
        if (reservationList.isEmpty()) {
            // If we are not currently holding any reservations, we need to create a new one.
            return new ReservedEventWindow(eventWindow, newReservation());
        }

        // Check to see if an existing reservation is good enough.

        final ShadowgraphReservation lastReservation = reservationList.getLast();

        final long previouslyReservedThreshold = lastReservation.getReservedThreshold();
        final long thresholdWeWantToReserve = eventWindow.getExpiredThreshold();

        if (previouslyReservedThreshold == thresholdWeWantToReserve) {

            // The latest reservation is against the same expired threshold that we currently want to reserve.
            // We can reuse that reservation instead of creating a new one. We still need to package that
            // reservation with the most recent eventWindow we know about.

            lastReservation.incrementReservations();
            return new ReservedEventWindow(eventWindow, lastReservation);
        } else {

            // We want a reservation on an expired threshold that isn't currently reserved.
            // Create a new reservation.

            return new ReservedEventWindow(eventWindow, newReservation());
        }
    }

    /**
     * Get the latest event window known to the shadowgraph.
     */
    @NonNull
    public synchronized EventWindow getEventWindow() {
        return eventWindow;
    }

    /**
     * Determines if the provided {@code hash} is in the shadowgraph.
     *
     * @param hash the hash to look for
     * @return true if the hash matches the hash of a shadow event in the shadowgraph, false otherwise
     * @deprecated still used by tests, planned for removal. Do not add new uses.
     */
    @Deprecated(forRemoval = true)
    public synchronized boolean isHashInGraph(final Hash hash) {
        return hashToShadowEvent.containsKey(hash);
    }

    /**
     * <p>Returns the ancestors of the provided {@code events} that pass the provided {@code predicate} using a
     * depth-first search. The provided {@code events} are not included in the return set. Searching stops at nodes that
     * have no parents, or nodes that do not pass the {@code predicate}.</p>
     *
     * <p>It is safe for this method not to be synchronized because:</p>
     * <ol>
     *     <li>this method does not modify any data</li>
     *     <li>adding events to the the graph does not affect ancestors</li>
     *     <li>checks for expired parent events are atomic</li>
     * </ol>
     * <p>Note: This method is always accessed after a call to a synchronized {@link Shadowgraph} method, like
     * {@link #getTips()}, which acts as a memory gate and causes the calling thread to read the latest values for all
     * variables from memory, including {@link ShadowEvent} links.</p>
     *
     * @param events    the event to find ancestors of
     * @param predicate determines whether or not to add the ancestor to the return list
     * @return the set of matching ancestors
     */
    public Set<ShadowEvent> findAncestors(final Iterable<ShadowEvent> events, final Predicate<ShadowEvent> predicate) {
        final HashSet<ShadowEvent> ancestors = new HashSet<>();
        for (ShadowEvent event : events) {
            // add ancestors that have not already been found and that pass the predicate
            final Predicate<ShadowEvent> isValidShadowEvent =
                    e -> !ancestors.contains(e) && !expired(e.getEvent().getDescriptor()) && predicate.test(e);
            findAncestors(ancestors, event, isValidShadowEvent);
        }
        return ancestors;
    }

    /**
     * Private method that searches for ancestors and takes a HashSet as input. This method exists for efficiency, when
     * looking for ancestors of multiple events, we want to append to the same HashSet.
     *
     * @param ancestors the HashSet to add ancestors to
     * @param event     the event to find ancestors of
     * @param predicate determines whether or not to add the ancestor to the return list
     */
    private void findAncestors(
            final HashSet<ShadowEvent> ancestors, final ShadowEvent event, final Predicate<ShadowEvent> predicate) {
        final Deque<ShadowEvent> todoStack = new ArrayDeque<>();

        final ShadowEvent sp = event.getSelfParent();
        if (sp != null) {
            todoStack.push(sp);
        }

        final ShadowEvent op = event.getOtherParent();
        if (op != null) {
            todoStack.push(op);
        }

        // perform a depth first search of self and other parents
        while (!todoStack.isEmpty()) {
            final ShadowEvent testEvent = todoStack.pop();
            /*
            IF

            1. the event is not expired
            AND
            2. the predicate passes
            AND
            3. the event is not already in ancestors,

            THEN

            add it to ancestors and push any non-null parents to the stack
             */
            if (!expired(testEvent.getEvent().getDescriptor())
                    && predicate.test(testEvent)
                    && ancestors.add(testEvent)) {
                final ShadowEvent xsp = testEvent.getSelfParent();
                if (xsp != null) {
                    todoStack.push(xsp);
                }
                final ShadowEvent xop = testEvent.getOtherParent();
                if (xop != null) {
                    todoStack.push(xop);
                }
            }
        }
    }

    /**
     * Looks for events in an ancient indicator range that pass the provided predicate.
     *
     * @param lowerBound the start of the range (inclusive)
     * @param upperBound the end of the range (exclusive)
     * @param predicate  the predicate to filter out events
     * @return a collection of events found
     * @deprecated planned for removal, do not add new uses
     */
    @Deprecated(forRemoval = true)
    @NonNull
    public synchronized Collection<PlatformEvent> findByAncientIndicator(
            final long lowerBound, final long upperBound, @NonNull final Predicate<PlatformEvent> predicate) {
        final List<PlatformEvent> result = new ArrayList<>();
        if (lowerBound >= upperBound) {
            return result;
        }
        for (long indicator = lowerBound; indicator < upperBound; indicator++) {
            indicatorToShadowEvent.getOrDefault(indicator, Collections.emptySet()).stream()
                    .map(ShadowEvent::getEvent)
                    .filter(predicate)
                    .forEach(result::add);
        }
        return result;
    }

    /**
     * <p>Update the reservable ancient indicator and remove any events from the shadowgraph that can and should be
     * expired.</p>
     *
     * <p>Events that should be expired have an ancient indicator that is less than {@code expireBelow}.</p>
     * <p>Events that are allowed to be expired are events:</p>
     * <ol>
     *     <li>whose ancient indicator has zero reservations</li>
     *     <li>whose ancient indicator is less than the smallest indicator with a non-zero number of reservations</li>
     * </ol>
     *
     * @param eventWindow describes the current window of non-expired events
     */
    public synchronized void updateEventWindow(@NonNull final EventWindow eventWindow) {
        if (this.eventWindow == null) {
            startWithEventWindow(eventWindow);
            return;
        }

        final long expiredThreshold = eventWindow.getExpiredThreshold();

        if (expiredThreshold < eventWindow.getExpiredThreshold()) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "A request to expire below {} is less than request of {}. Ignoring expiration request",
                    expiredThreshold,
                    eventWindow.getExpiredThreshold());
            // The value of expireBelow must never decrease, so if we receive an invalid request like this, ignore it
            return;
        }
        this.eventWindow = eventWindow;

        // Remove reservations for events that can and should be expired, and
        // keep track of the oldest threshold that can be expired
        long oldestReservedIndicator = pruneReservationList();

        if (oldestReservedIndicator == NO_RESERVATION) {
            oldestReservedIndicator = eventWindow.getExpiredThreshold();
        }

        metrics.updateIndicatorsWaitingForExpiry(eventWindow.getExpiredThreshold() - oldestReservedIndicator);

        // Expire events that can and should be expired, starting with the oldest non-expired ancient indicator
        // and working up until we reach an indicator that should not or cannot be expired.
        //
        // This process must be separate from iterating through the reservations because even if there are no
        // reservations, expiry should still function correctly.

        final long minimumIndicatorToKeep = Math.min(eventWindow.getExpiredThreshold(), oldestReservedIndicator);

        while (oldestUnexpiredIndicator < minimumIndicatorToKeep) {
            final Set<ShadowEvent> shadowsToExpire = indicatorToShadowEvent.remove(oldestUnexpiredIndicator);
            if (shadowsToExpire != null) {
                shadowsToExpire.forEach(this::expire);
            }
            oldestUnexpiredIndicator++;
        }
    }

    /**
     * Removes reservations that can and should be expired, starting with the oldest ancient indicator reservation.
     *
     * @return the oldest ancient indicator with at least one reservation, or {@code -1} if there are no reservations
     */
    private long pruneReservationList() {
        long oldestReservedIndicator = NO_RESERVATION;

        // Iterate through the reservation list in ascending ancient indicator order, removing reservations
        // for indicators that can and should be expired.
        final Iterator<ShadowgraphReservation> iterator = reservationList.iterator();
        while (iterator.hasNext()) {
            final ShadowgraphReservation reservation = iterator.next();
            final long reservedIndicator = reservation.getReservedThreshold();

            if (reservation.getReservationCount() > 0) {
                // As soon as we find a reserved indicator, stop iterating
                oldestReservedIndicator = reservation.getReservedThreshold();
                break;
            } else if (reservedIndicator < eventWindow.getExpiredThreshold()) {
                // If the number of reservations is 0 and the
                // indicator should be expired, remove the reservation
                iterator.remove();
            } else {
                // If the expireBelow indicator is reached, stop
                // because no more indicators should be expired
                break;
            }
        }
        return oldestReservedIndicator;
    }

    /**
     * Expires a single {@link ShadowEvent} from the shadowgraph.
     *
     * @param shadow the shadow event to expire
     */
    private void expire(final ShadowEvent shadow) {
        // Remove the shadow from the shadowgraph
        hashToShadowEvent.remove(shadow.getEventBaseHash());
        // Remove references to parent shadows so this event gets garbage collected
        shadow.disconnect();
        tips.remove(shadow);
    }

    /**
     * Get the shadow event that references a hashgraph otherParent instance.
     *
     * @param otherParentsDescriptors List of event descriptors.
     * @return the shadow event that references an event, or null if {@code otherParentsDescriptors} is empty
     * @throws IllegalArgumentException if {@code otherParentsDescriptors} contains more than one event descriptor
     */
    @Nullable
    private synchronized ShadowEvent shadow(@NonNull final List<EventDescriptorWrapper> otherParentsDescriptors) {
        if (otherParentsDescriptors.isEmpty()) {
            return null;
        }

        if (otherParentsDescriptors.size() > 1) {
            throw new IllegalArgumentException("Only one otherParent descriptor is supported");
        }

        return hashToShadowEvent.get(otherParentsDescriptors.getFirst().hash());
    }

    /**
     * Get the shadow event that references a hashgraph event instance.
     *
     * @param e The event.
     * @return the shadow event that references an event, or null is {@code e} is null
     */
    @Nullable
    public synchronized ShadowEvent shadow(@Nullable final EventDescriptorWrapper e) {
        if (e == null) {
            return null;
        }

        return hashToShadowEvent.get(e.hash());
    }

    /**
     * Get the shadow events that reference the hashgraph event instances with the given hashes.
     *
     * @param hashes The event hashes to get shadow events for
     * @return the shadow events that reference the events with the given hashes
     */
    public synchronized List<ShadowEvent> shadows(final List<Hash> hashes) {
        Objects.requireNonNull(hashes);
        final List<ShadowEvent> shadows = new ArrayList<>(hashes.size());
        for (final Hash hash : hashes) {
            shadows.add(shadow(hash));
        }
        return shadows;
    }

    /**
     * Get a hashgraph event from a hash
     *
     * @param h the hash
     * @return the hashgraph event, if there is one in {@code this} shadowgraph, else `null`
     */
    @Nullable
    public synchronized PlatformEvent hashgraphEvent(@Nullable final Hash h) {
        final ShadowEvent shadow = shadow(h);
        if (shadow == null) {
            return null;
        } else {
            return shadow.getEvent();
        }
    }

    /**
     * Returns a copy of the tips at the time of invocation. The returned list is not affected by changes made to the
     * tip set.
     *
     * @return an unmodifiable copy of the tips
     */
    @NonNull
    public synchronized List<ShadowEvent> getTips() {
        return new ArrayList<>(tips);
    }

    /**
     * If Event `event` is insertable, then insert it and update the tip set, else do nothing.
     *
     * @param event The event reference to insert.
     * @return {@code true} if the event was added, {@code false} otherwise
     * @throws ShadowgraphInsertionException if the event was unable to be added to the shadowgraph
     */
    public synchronized boolean addEvent(@NonNull final PlatformEvent event) throws ShadowgraphInsertionException {
        if (eventWindow == null) {
            throw new IllegalStateException("Initial event window not set");
        }
        Objects.requireNonNull(event);
        try {
            final InsertableStatus status = insertable(event);

            if (status == InsertableStatus.INSERTABLE) {
                final int tipsBefore = tips.size();
                final ShadowEvent s = insert(event);
                tips.add(s);
                tips.remove(s.getSelfParent());

                if (numberOfNodes > 0 && tips.size() > numberOfNodes && tips.size() > tipsBefore) {
                    // It is possible that we have more tips than nodes even if there is no fork.
                    // Explained in: sync-protocol.md
                    logger.info(
                            SYNC_INFO.getMarker(),
                            "tips size is {} after adding {}. Esp null:{} Ssp null:{}\n"
                                    + "eventWindow.getExpiredThreshold: {} oldestUnexpiredIndicator: {}\n"
                                    + "current tips:{}",
                            tips::size,
                            () -> event,
                            () -> event.getSelfParent() == null,
                            () -> s.getSelfParent() == null,
                            () -> eventWindow.getExpiredThreshold(),
                            () -> oldestUnexpiredIndicator,
                            () -> tips.stream()
                                    .map(sh -> sh.getEvent().getDescriptor().toString())
                                    .collect(Collectors.joining(",")));
                }

                return true;
            } else {
                if (status == InsertableStatus.EXPIRED_EVENT) {
                    logger.error(
                            EXCEPTION.getMarker(),
                            "`addEvent`: did not insert, status is {} for event {}, oldestUnexpiredIndicator = {}",
                            status,
                            event,
                            oldestUnexpiredIndicator);
                    return false;
                } else if (status == InsertableStatus.NULL_EVENT) {
                    throw new ShadowgraphInsertionException(
                            String.format("`addEvent`: did not insert, status is %s", status), status);
                } else {
                    throw new ShadowgraphInsertionException(
                            String.format(
                                    "`addEvent`: did not insert, status is %s for event %s, oldestUnexpiredIndicator = %s",
                                    status, event, oldestUnexpiredIndicator),
                            status);
                }
            }
        } finally {
            intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
        }
    }

    private ShadowgraphReservation newReservation() {
        final ShadowgraphReservation reservation = new ShadowgraphReservation(eventWindow.getExpiredThreshold());
        reservationList.addLast(reservation);
        return reservation;
    }

    private ShadowEvent shadow(final Hash h) {
        return hashToShadowEvent.get(h);
    }

    /**
     * @param hash the hash of the event
     * @return the event that has the hash provided, or null if none exists
     */
    @Nullable
    public synchronized PlatformEvent getEvent(@Nullable final Hash hash) {
        final ShadowEvent shadowEvent = hashToShadowEvent.get(hash);
        return shadowEvent == null ? null : shadowEvent.getEvent();
    }

    /**
     * Attach a shadow of a Hashgraph event to this graph. Only a shadow for which a parent hash matches a hash in
     * this@entry is inserted.
     *
     * @param event The Hashgraph event shadow to be inserted
     * @return the inserted shadow event
     */
    @NonNull
    private ShadowEvent insert(@NonNull final PlatformEvent event) {
        final ShadowEvent sp = shadow(event.getSelfParent());
        final ShadowEvent op = shadow(event.getOtherParents());

        final ShadowEvent se = new ShadowEvent(event, sp, op);

        hashToShadowEvent.put(se.getEventBaseHash(), se);

        final long ancientIndicator = ancientMode.selectIndicator(event);
        if (!indicatorToShadowEvent.containsKey(ancientIndicator)) {
            indicatorToShadowEvent.put(ancientIndicator, new HashSet<>());
        }
        indicatorToShadowEvent.get(ancientIndicator).add(se);

        return se;
    }

    /**
     * Predicate to determine if an event has expired.
     *
     * @param event The event.
     * @return true iff the given event is expired
     */
    private boolean expired(final EventDescriptorWrapper event) {
        return event.getAncientIndicator(ancientMode) < oldestUnexpiredIndicator;
    }

    /*
     * Given an Event, `e`, with parent p, where p may be either self-parent or other-parent, the
     * following test is applied to the parent p:
     *
     *   has parent   known parent   expired parent      insertable
     *   --------------------------------------------+------------
     *   false                                       |   true
     *   true         false          false           |   false
     *   true         true           false           |   true
     *   true         false          true            |   true
     *   true         true           true            |   true
     *
     * and the following test is applied to the event `e`:
     *
     *   is null      null shadow    expired             insertable
     *   --------------------------------------------+------------
     *   true                                        |   false
     *                true                           |   false
     *                               true            |   false
     *   false        false          false           |   true
     *
     * The parent test above is applied to both of self-parent and other-parent. If the test
     * is false for either parent, then `e` is not insertable. Else, `e` is insertable.
     *
     * I.e., the expression
     *
     *  test(`e`) && test(self-parent of `e`) && test(other-parent of `e`)
     *
     * is evaluated, where "test" is as defined above for the event `e` and for its parents.
     * The result of that evaluation  determines whether `e` is insertable.
     *
     * return: true iff e is to be inserted
     *
     */

    /**
     * Determine whether an event is insertable at time of call.
     *
     * @param e The event to evaluate
     * @return An insertable status, indicating whether the event can be inserted, and if not, the reason it can not be
     * inserted.
     */
    @NonNull
    private InsertableStatus insertable(@NonNull final PlatformEvent e) {
        // No multiple insertions
        if (shadow(e.getDescriptor()) != null) {
            return InsertableStatus.DUPLICATE_SHADOW_EVENT;
        }

        // An expired event will not be referenced in the graph.
        if (expired(e.getDescriptor())) {
            return InsertableStatus.EXPIRED_EVENT;
        }

        final boolean hasOP = !e.getOtherParents().isEmpty();
        final boolean hasSP = e.getSelfParent() != null;

        // If e has an unexpired parent that is not already referenced by the shadowgraph, then we log an error. This
        // is only a sanity check, so there is no need to prevent insertion
        if (hasOP) {
            if (e.getOtherParents().size() > 1) {
                throw new IllegalStateException("Only one otherParent descriptor is supported");
            }
            final EventDescriptorWrapper otherParent = e.getOtherParents().getFirst();
            final boolean knownOP = shadow(otherParent) != null;
            final boolean expiredOP = expired(otherParent);
            if (!knownOP && !expiredOP) {
                logger.info(STARTUP.getMarker(), "Missing non-expired other parent for {}", e);
            }
        }

        if (hasSP) {
            final boolean knownSP = shadow(e.getSelfParent()) != null;
            final boolean expiredSP = expired(e.getSelfParent());
            if (!knownSP && !expiredSP) {
                logger.info(STARTUP.getMarker(), "Missing non-expired self parent for {}", e);
            }
        }

        // If both parents are null, then insertion is allowed. This will create
        // a new tree in the forest view of the graph.
        return InsertableStatus.INSERTABLE;
    }
}
