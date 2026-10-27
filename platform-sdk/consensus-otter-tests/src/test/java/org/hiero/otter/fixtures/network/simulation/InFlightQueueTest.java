// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InFlightQueue Test")
class InFlightQueueTest {

    /** Deliberately not on a whole second, so that converting to nanoseconds has to borrow across seconds. */
    private static final Instant START = Instant.parse("2024-01-01T00:00:00.750Z");

    private static final NodeId SENDER = NodeId.of(1);

    /** The structure under test. */
    private final InFlightQueue<EventWindowInTransit> queue = new InFlightQueue<>(START);

    /**
     * Makes every item distinct. The items are records, so two with the same window, sender and arrival time would be
     * equal, and a test of the order of items arriving at the same instant could not tell them apart.
     */
    private long nextItemId;

    @Test
    @DisplayName("an empty queue has nothing to peek at or poll, and nothing has arrived")
    void anEmptyQueueHasNothing() {
        assertThat(queue.peek()).isNull();
        assertThat(queue.poll()).isNull();
        assertThat(queue.hasArrived(Long.MAX_VALUE)).isFalse();
    }

    @Test
    @DisplayName("peeking returns the head without removing it, and polling removes and returns it")
    void peekAndPoll() {
        final EventWindowInTransit item = add(10);

        assertThat(queue.peek()).isSameAs(item);
        assertThat(queue.peek()).isSameAs(item);
        assertThat(queue.poll()).isSameAs(item);
        assertThat(queue.peek()).isNull();
    }

    @Test
    @DisplayName("items come out in arrival order, whatever order they were added in")
    void itemsComeOutInArrivalOrder() {
        final EventWindowInTransit late = add(30);
        final EventWindowInTransit early = add(10);
        final EventWindowInTransit middle = add(20);

        assertThat(drain()).containsExactly(early, middle, late);
    }

    @Test
    @DisplayName("items arriving at the same instant come out in the order they were added")
    void tiesKeepInsertionOrder() {
        final EventWindowInTransit first = add(10);
        final EventWindowInTransit second = add(10);
        final EventWindowInTransit earlier = add(5);
        final EventWindowInTransit third = add(10);

        assertThat(drain()).containsExactly(earlier, first, second, third);
    }

    @Test
    @DisplayName("items one nanosecond apart are told apart")
    void nanosecondDifferencesAreRespected() {
        final EventWindowInTransit later = addAt(START.plusNanos(1));
        final EventWindowInTransit earlier = addAt(START);

        assertThat(drain()).containsExactly(earlier, later);
    }

    @Test
    @DisplayName("an item added again after being polled goes behind items already waiting at the same instant")
    void reAddingAfterPollingGoesToTheBackOfItsTie() {
        // InFlightEventWindows puts a refused window back like this
        final EventWindowInTransit refused = add(10);
        final EventWindowInTransit waiting = add(10);

        assertThat(queue.poll()).isSameAs(refused);
        queue.add(refused);

        assertThat(drain()).containsExactly(waiting, refused);
    }

    @Test
    @DisplayName("many items, with many ties, come out in arrival and then insertion order, past the initial capacity")
    void manyItemsComeOutInOrder() {
        final Random random = new Random(0);
        final List<EventWindowInTransit> added = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            // few distinct arrival times, so that many items tie and the insertion order has to decide
            added.add(add(random.nextInt(50)));
        }

        // a stable sort keeps the insertion order among equal arrival times
        final List<EventWindowInTransit> expected = added.stream()
                .sorted(Comparator.comparing(EventWindowInTransit::arrivalTime))
                .toList();
        assertThat(drain()).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("adding and polling in turns keeps the arrival order")
    void interleavedAddsAndPollsKeepOrder() {
        final Random random = new Random(1);
        final List<EventWindowInTransit> polled = new ArrayList<>();
        long clock = 0;
        for (int round = 0; round < 200; round++) {
            for (int i = 0; i < 3; i++) {
                add(clock + random.nextInt(100));
            }
            // poll everything that has arrived by now, the way delivery does
            final long now = InFlightQueue.nanosSince(START, START.plusMillis(clock));
            while (queue.hasArrived(now)) {
                polled.add(queue.poll());
            }
            clock += 10;
        }
        polled.addAll(drain());

        assertThat(polled).hasSize(600).isSortedAccordingTo(Comparator.comparing(EventWindowInTransit::arrivalTime));
    }

    @Test
    @DisplayName("clearing discards everything, and the queue can be used again afterwards")
    void clearingEmptiesTheQueueForReuse() {
        add(10);
        add(20);

        queue.clear();
        assertThat(queue.peek()).isNull();
        assertThat(queue.hasArrived(Long.MAX_VALUE)).isFalse();

        final EventWindowInTransit late = add(40);
        final EventWindowInTransit tiedFirst = add(30);
        final EventWindowInTransit tiedSecond = add(30);
        assertThat(drain()).containsExactly(tiedFirst, tiedSecond, late);
    }

    @Test
    @DisplayName("the head has arrived once the current time reaches its arrival time, and not before")
    void hasArrivedIsInclusive() {
        add(10);
        final long arrival = InFlightQueue.nanosSince(START, START.plusMillis(10));

        assertThat(queue.hasArrived(arrival - 1)).isFalse();
        assertThat(queue.hasArrived(arrival)).isTrue();
        assertThat(queue.hasArrived(arrival + 1)).isTrue();
    }

    @Test
    @DisplayName("whether the head has arrived follows the head as items are added and polled")
    void hasArrivedFollowsTheHead() {
        final long now = InFlightQueue.nanosSince(START, START.plusMillis(15));
        add(20);
        assertThat(queue.hasArrived(now)).isFalse();

        add(10);
        assertThat(queue.hasArrived(now)).isTrue();

        queue.poll();
        assertThat(queue.hasArrived(now)).isFalse();
    }

    @Test
    @DisplayName("the head's arrival time follows the head as items are added and polled")
    void headArrivalFollowsTheHead() {
        add(20);
        assertThat(queue.headArrival()).isEqualTo(millisAfterStart(20));

        // an item that arrives later does not change the head
        add(30);
        assertThat(queue.headArrival()).isEqualTo(millisAfterStart(20));

        // an item that arrives earlier becomes the head
        add(10);
        assertThat(queue.headArrival()).isEqualTo(millisAfterStart(10));

        queue.poll();
        assertThat(queue.headArrival()).isEqualTo(millisAfterStart(20));
        queue.poll();
        assertThat(queue.headArrival()).isEqualTo(millisAfterStart(30));
    }

    @Test
    @DisplayName("queues given the same start compare their heads on the same scale")
    void queuesWithTheSameStartAreComparable() {
        final InFlightQueue<EventWindowInTransit> other = new InFlightQueue<>(START);
        queue.add(item(START.plusMillis(20)));
        other.add(item(START.plusMillis(10)));

        assertThat(other.headArrival()).isLessThan(queue.headArrival());
    }

    @Test
    @DisplayName("an instant converts to the nanoseconds since the start, before or after it")
    void nanosSinceMeasuresFromTheStart() {
        assertThat(InFlightQueue.nanosSince(START, START)).isZero();
        assertThat(InFlightQueue.nanosSince(START, START.plusNanos(1))).isEqualTo(1);
        assertThat(InFlightQueue.nanosSince(START, START.minusNanos(1))).isEqualTo(-1);
        // crosses into the next second, so the nanoseconds within the second wrap round
        assertThat(InFlightQueue.nanosSince(START, START.plusMillis(500))).isEqualTo(500_000_000L);
        assertThat(InFlightQueue.nanosSince(START, START.plusSeconds(3600).plusNanos(7)))
                .isEqualTo(3_600_000_000_007L);
        assertThat(InFlightQueue.nanosSince(START, START.minusSeconds(2))).isEqualTo(-2_000_000_000L);
    }

    @Test
    @DisplayName("an instant too far from the start to fit in a long is clamped")
    void nanosSinceClampsFarInstants() {
        assertThat(InFlightQueue.nanosSince(START, Instant.MIN)).isEqualTo(Long.MIN_VALUE);
        assertThat(InFlightQueue.nanosSince(START, Instant.MAX)).isEqualTo(Long.MAX_VALUE);
        // just inside the range is still exact
        assertThat(InFlightQueue.nanosSince(START, START.plusSeconds(9_000_000_000L)))
                .isEqualTo(9_000_000_000L * 1_000_000_000L);
    }

    @Test
    @DisplayName("an item sent before the network's first tick, while its clock reads Instant.MIN, has arrived")
    void anItemFromBeforeTheFirstTickHasArrived() {
        final EventWindowInTransit early = addAt(Instant.MIN.plusMillis(5));
        final EventWindowInTransit normal = add(10);

        assertThat(queue.hasArrived(InFlightQueue.nanosSince(START, START))).isTrue();
        assertThat(drain()).containsExactly(early, normal);
    }

    /**
     * Adds an item that arrives the given number of milliseconds after the start.
     *
     * @param arrivalMillis when the item arrives, in milliseconds after the start
     * @return the item added
     */
    private EventWindowInTransit add(final long arrivalMillis) {
        return addAt(START.plusMillis(arrivalMillis));
    }

    /**
     * Adds an item that arrives at the given instant.
     *
     * @param arrival when the item arrives
     * @return the item added
     */
    private EventWindowInTransit addAt(@NonNull final Instant arrival) {
        final EventWindowInTransit item = item(arrival);
        queue.add(item);
        return item;
    }

    /**
     * Polls every item out of the queue.
     *
     * @return the items in the order they came out
     */
    private List<EventWindowInTransit> drain() {
        final List<EventWindowInTransit> drained = new ArrayList<>();
        for (EventWindowInTransit item = queue.poll(); item != null; item = queue.poll()) {
            drained.add(item);
        }
        return drained;
    }

    private static long millisAfterStart(final long millis) {
        return millis * 1_000_000L;
    }

    private EventWindowInTransit item(@NonNull final Instant arrival) {
        final long id = nextItemId++;
        return new EventWindowInTransit(new EventWindow(id, id + 1, 1, 1), SENDER, arrival);
    }
}
