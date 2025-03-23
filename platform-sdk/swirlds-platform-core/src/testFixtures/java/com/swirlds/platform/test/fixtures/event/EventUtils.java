// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event;

import static java.lang.Integer.max;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

public final class EventUtils {
    /**
     * Hidden constructor
     */
    private EventUtils() {}

    /**
     * Serialize a platform event to a byte array.
     *
     * @param event the event to serialize
     * @return the serialized event
     */
    public static byte[] serializePlatformEvent(@NonNull final PlatformEvent event) {
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
            new SerializableDataOutputStreamImpl(stream).writePbjRecord(event.getGossipEvent(), GossipEvent.PROTOBUF);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return stream.toByteArray();
    }

    /**
     * Choose a random integer given a list of probabilistic weights.
     *
     * @param weights a list of weights. Each weight must be positive. Sum of all weights must be
     *     greater than 0.
     * @return an integer between 0 (inclusive) and weights.size() (exclusive)
     */
    public static int weightedChoice(final Random random, final List<Double> weights) {

        double totalWeight = 0.0;
        for (final Double weight : weights) {
            totalWeight += weight;
        }

        if (totalWeight <= 0.0) {
            throw new IllegalArgumentException("Total weight must be greater than 0.0.");
        }

        // FUTURE WORK this can be done in logn time with a binary search

        final double randomValue = random.nextDouble() * totalWeight;
        double sum = 0.0;
        int choice = -1;
        for (int index = 0; index < weights.size(); index++) {
            choice = index;
            sum += weights.get(index);
            if (sum > randomValue) {
                break;
            }
        }

        return choice;
    }

    /**
     * Check to see if all events have increasing generation numbers for each node.
     */
    public static boolean areGenerationNumbersValid(
            @NonNull final Iterable<EventImpl> events, final int numberOfNodes) {
        Objects.requireNonNull(events, "events must not be null");
        final Map<NodeId, Long> previousGenNumber = new HashMap<>(numberOfNodes);

        for (final EventImpl event : events) {
            final NodeId nodeId = event.getCreatorId();
            if (previousGenNumber.containsKey(nodeId)) {
                if (previousGenNumber.get(nodeId) >= event.getGeneration()) {
                    return false;
                }
            }
            previousGenNumber.put(nodeId, event.getGeneration());
        }
        return true;
    }

    /** Given a list of events, check if each event comes after all of its ancestors. */
    public static boolean isEventOrderValid(final List<EventImpl> events) {
        final Set<EventImpl> eventsEncountered = new HashSet<>();

        for (final EventImpl event : events) {
            final EventImpl selfParent = event.getSelfParent();
            final EventImpl otherParent = event.getOtherParent();

            if (selfParent != null) {
                if (!eventsEncountered.contains(selfParent)) {
                    return false;
                }
            }

            if (otherParent != null) {
                if (!eventsEncountered.contains(otherParent)) {
                    return false;
                }
            }

            eventsEncountered.add(event);
        }
        return true;
    }

    /**
     * Sort a list of events on generator ID.
     *
     * @param events An unsorted list of events.
     * @return A sorted list of events.
     */
    public static List<EventImpl> sortEventList(final List<EventImpl> events) {
        final List<EventImpl> sortedEvents = new ArrayList<>(events);
        sortedEvents.sort(Comparator.comparing(EventImpl::getBaseHash));
        return sortedEvents;
    }

    /** Check if two event lists contain the same values (but in a possibly different order). */
    public static boolean areEventListsEquivalent(List<EventImpl> events1, List<EventImpl> events2) {
        events1 = sortEventList(events1);
        events2 = sortEventList(events2);
        return events1.equals(events2);
    }

    /**
     * A "dynamic" value that actually returns a static constant.
     *
     * @param staticValue the value to always return
     */
    public static <T> DynamicValue<T> staticDynamicValue(final T staticValue) {
        return (Random random, long eventIndex, T previousValue) -> staticValue;
    }

    /**
     * A dynamic integer that follows a power distribution.
     *
     * <p>P(0) = alpha P(1) = (1 - alpha) * alpha P(2) = (1 - alpha)^2 * alpha P(3) = (1 - alpha)^3
     * * alpha ... P(n) = (1 - alpha)^n * alpha
     */
    public static DynamicValue<Integer> integerPowerDistribution(final double alpha) {
        return (Random random, long eventIndex, Integer previousValue) -> {
            int ret = 0;
            while (random.nextDouble() > alpha) {
                ret++;
            }
            return ret;
        };
    }

    /**
     * Same as integerPowerDistribution above, except if the value is below a minimum the minimum is
     * used.
     */
    public static DynamicValue<Integer> integerPowerDistribution(final double alpha, final int minimum) {
        return (Random random, long eventIndex, Integer previousValue) -> {
            int ret = 0;
            while (random.nextDouble() > alpha) {
                ret++;
            }
            return max(ret, minimum);
        };
    }

    /**
     * Calculate the age of an event's other parent. Helper method for gatherOtherParentAges.
     *
     * @param events a list of events
     * @param eventIndex the index of the event to be considered. The age of the event's other
     *     parent is returned.
     */
    private static int calculateOtherParentAge(final List<EventImpl> events, final int eventIndex) {

        final EventImpl event = events.get(eventIndex);
        final EventImpl otherParent = event.getOtherParent();
        if (otherParent == null) {
            return 0;
        }
        final NodeId otherParentNode = otherParent.getCreatorId();

        int age = 0;
        for (int index = eventIndex - 1; index >= 0; index--) {
            final EventImpl nextEvent = events.get(index);
            if (nextEvent == otherParent) {
                break;
            }
            if (Objects.equals(nextEvent.getCreatorId(), otherParentNode)) {
                age++;
            }
        }

        return age;
    }

    /**
     * For each event, check the "age" of the other parent. Compile age data into a map.
     *
     * <p>The age of an other parent event is defined as follows: 0 = the other parent is the most
     * recent event from it's parent node 1 = the other parent is the second most recent event from
     * its other parent etc.
     *
     * <p>This method will not work correctly in the presence of forking. Age may not be constant
     * after shuffling node order.
     *
     * @param events a list of events
     * @param excludedNodes if not null, do not include data about the other parents of events
     *     created by the node IDs in this set
     * @return A map: {age : number of events with that age}
     */
    public static Map<Integer, Integer> gatherOtherParentAges(
            final List<EventImpl> events, final Set<NodeId> excludedNodes) {
        final Map<Integer, Integer> map = new HashMap<>();
        for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {

            if (excludedNodes != null) {
                if (excludedNodes.contains(events.get(eventIndex).getCreatorId())) {
                    continue;
                }
            }

            final int age = calculateOtherParentAge(events, eventIndex);
            if (!map.containsKey(age)) {
                map.put(age, 0);
            }
            map.put(age, map.get(age) + 1);
        }
        return map;
    }
}
