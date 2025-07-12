// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.linking;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import com.swirlds.platform.event.EventCounter;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.sequence.map.SequenceMap;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;

/**
 * Links events to their parents. Expects events to be provided in topological order.
 * <p>
 * Will not link events to parents in the following cases:
 * <ul>
 *     <li>The parent is ancient</li>
 *     <li>The parent's generation does not match the generation claimed by the child event</li>
 *     <li>The parent's time created is greater than or equal to the child's time created</li>
 * </ul>
 */
abstract class AbstractInOrderLinker implements InOrderLinker {

    /**
     * The initial capacity of the {@link #parentDescriptorMap} and {@link #parentHashMap}
     */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * The minimum period between log messages for a specific mode of failure.
     */
    private static final Duration MINIMUM_LOG_PERIOD = Duration.ofMinutes(1);

    private final RateLimitedLogger missingParentLogger;
    private final RateLimitedLogger birthRoundMismatchLogger;
    private final RateLimitedLogger timeCreatedMismatchLogger;

    /**
     * A sequence map from event descriptor to event.
     * <p>
     * The window of this map is shifted when the minimum non-ancient threshold is changed, so that only non-ancient
     * events are retained.
     */
    private final SequenceMap<EventDescriptorWrapper, EventImpl> parentDescriptorMap;

    /**
     * A map from event hash to event.
     * <p>
     * This map is needed in addition to the sequence map, since we need to be able to look up parent events based on
     * hash. Elements are removed from this map when the window of the sequence map is shifted.
     */
    private final Map<Hash, EventImpl> parentHashMap = new HashMap<>(INITIAL_CAPACITY);

    /**
     * The current event window.
     */
    private EventWindow eventWindow;

    /**
     * Constructor
     *
     * @param platformContext the platform context
     */
    public AbstractInOrderLinker(@NonNull final PlatformContext platformContext) {
        // We use a non-static logger here so that we can scope the logger to the concrete
        // implementation of this class, not to the abstract class. Once instantiated, a
        // linker has the same life span as a node, so it is not inefficient to
        // have a non-static logger.
        final Logger logger = LogManager.getLogger(this.getClass());

        this.missingParentLogger = new RateLimitedLogger(logger, platformContext.getTime(), MINIMUM_LOG_PERIOD);
        this.birthRoundMismatchLogger = new RateLimitedLogger(logger, platformContext.getTime(), MINIMUM_LOG_PERIOD);
        this.timeCreatedMismatchLogger = new RateLimitedLogger(logger, platformContext.getTime(), MINIMUM_LOG_PERIOD);

        this.eventWindow = EventWindow.getGenesisEventWindow();
        this.parentDescriptorMap =
                new StandardSequenceMap<>(0, INITIAL_CAPACITY, true, EventDescriptorWrapper::birthRound);
    }

    /**
     * Find and link the parents of the given event.
     *
     * @param event the event to link
     * @return the linked event, or null if the event is ancient
     */
    @Nullable
    public EventImpl linkEvent(@NonNull final PlatformEvent event) {
        if (eventWindow.isAncient(event)) {
            // This event is ancient, so we don't need to link it.
            ancientEventAdded(event);
            return null;
        }

        final EventImpl selfParent = getParentToLink(event, event.getSelfParent());

        // FUTURE WORK: Extend other parent linking to support multiple other parents.
        // Until then, take the first parent in the list.
        final List<EventDescriptorWrapper> otherParents = event.getOtherParents();
        final EventImpl otherParent = otherParents.isEmpty() ? null : getParentToLink(event, otherParents.get(0));

        final EventImpl linkedEvent = new EventImpl(event, selfParent, otherParent);
        EventCounter.incrementLinkedEventCount();

        final EventDescriptorWrapper eventDescriptorWrapper = event.getDescriptor();
        parentDescriptorMap.put(eventDescriptorWrapper, linkedEvent);
        parentHashMap.put(eventDescriptorWrapper.hash(), linkedEvent);

        return linkedEvent;
    }

    /**
     * Set the event window, defining the minimum non-ancient threshold.
     *
     * @param eventWindow the event window
     */
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = Objects.requireNonNull(eventWindow);

        parentDescriptorMap.shiftWindow(eventWindow.ancientThreshold(), (descriptor, event) -> {
            parentHashMap.remove(descriptor.hash());
            eventHasBecomeAncient(event);
        });
    }

    /**
     * Clear the internal state of this linker.
     */
    @Override
    public void clear() {
        parentDescriptorMap.clear();
        parentHashMap.clear();
    }

    /**
     * This method is called when a child event has a missing parent.
     *
     * @param child            the child event
     * @param parentDescriptor the descriptor of the missing parent
     */
    protected void childHasMissingParent(
            @NonNull final PlatformEvent child, @NonNull final EventDescriptorWrapper parentDescriptor) {
        missingParentLogger.error(
                EXCEPTION.getMarker(),
                "Child has a missing parent. This should not be possible. Child: {}, Parent EventDescriptor: {}",
                child,
                parentDescriptor);
    }

    /**
     * This method is called when a child event has a parent with a different birth round than claimed.
     *
     * @param child            the child event
     * @param parentDescriptor the claimed descriptor of the parent
     * @param candidateParent  the parent event that we found in the parentHashMap
     */
    protected void parentHasIncorrectBirthRound(
            @NonNull final PlatformEvent child,
            @NonNull final EventDescriptorWrapper parentDescriptor,
            @NonNull final EventImpl candidateParent) {
        birthRoundMismatchLogger.warn(
                EXCEPTION.getMarker(),
                "Event has a parent with a different birth round than claimed. Child: {}, parent: {}, "
                        + "claimed birth round: {}, actual birth round: {}",
                child,
                candidateParent,
                parentDescriptor.eventDescriptor().birthRound(),
                candidateParent.getBirthRound());
    }

    /**
     * This method is called when a child event has a self parent with a time created that is not strictly before the
     * child's time created.
     *
     * @param child             the child event
     * @param candidateParent   the parent event that we found in the parentHashMap
     * @param parentTimeCreated the time created of the parent event
     * @param childTimeCreated  the time created of the child event
     */
    protected void childTimeIsNotAfterSelfParentTime(
            @NonNull final PlatformEvent child,
            @NonNull final EventImpl candidateParent,
            @NonNull final Instant parentTimeCreated,
            @NonNull final Instant childTimeCreated) {
        timeCreatedMismatchLogger.error(
                EXCEPTION.getMarker(),
                "Child time created isn't strictly after self parent time created. "
                        + "Child: {}, parent: {}, child time created: {}, parent time created: {}",
                child,
                candidateParent,
                childTimeCreated,
                parentTimeCreated);
    }

    /**
     * This method is called when an event is discarded because it is ancient when it is first added.
     *
     * @param event the event that was discarded
     */
    protected void ancientEventAdded(@NonNull final PlatformEvent event) {
        // Implement this if extra action is needed.
    }

    /**
     * This method is called when this data structure stops tracking an event because it has become ancient.
     *
     * @param event the event that has become ancient
     */
    protected void eventHasBecomeAncient(@NonNull final EventImpl event) {
        // Implement this if extra action is needed.
    }

    /**
     * Find the correct parent to link to a child. If a parent should not be linked, null is returned.
     * <p>
     * A parent should not be linked if any of the following are true:
     * <ul>
     *     <li>The parent is ancient</li>
     *     <li>The parent's generation does not match the generation claimed by the child event</li>
     *     <li>The parent's birthRound does not match the claimed birthRound by the child event</li>
     *     <li>The parent's time created is greater than or equal to the child's time created</li>
     * </ul>
     *
     * @param child            the child event
     * @param parentDescriptor the event descriptor for the claimed parent
     * @return the parent to link, or null if no parent should be linked
     */
    @Nullable
    private EventImpl getParentToLink(
            @NonNull final PlatformEvent child, @Nullable final EventDescriptorWrapper parentDescriptor) {

        if (parentDescriptor == null) {
            // There is no claimed parent for linking.
            return null;
        }

        if (eventWindow.isAncient(parentDescriptor)) {
            // ancient parents don't need to be linked
            return null;
        }

        final EventImpl candidateParent = parentHashMap.get(parentDescriptor.hash());
        if (candidateParent == null) {
            childHasMissingParent(child, parentDescriptor);
            return null;
        }

        if (candidateParent.getBirthRound()
                != parentDescriptor.eventDescriptor().birthRound()) {
            parentHasIncorrectBirthRound(child, parentDescriptor, candidateParent);
            return null;
        }

        final Instant parentTimeCreated = candidateParent.getBaseEvent().getTimeCreated();
        final Instant childTimeCreated = child.getTimeCreated();

        // only do this check for self parent, since the event creator doesn't consider other parent creation time
        // when deciding on the event creation time
        if (parentDescriptor.creator().equals(child.getDescriptor().creator())
                && parentTimeCreated.compareTo(childTimeCreated) >= 0) {

            childTimeIsNotAfterSelfParentTime(child, candidateParent, parentTimeCreated, childTimeCreated);

            return null;
        }

        return candidateParent;
    }
}
