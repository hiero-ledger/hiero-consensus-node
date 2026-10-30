// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConnectionHeap Test")
class ConnectionHeapTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    /** The structure under test. */
    private final ConnectionHeap<EventWindowInTransit> heap = new ConnectionHeap<>();

    @Test
    @DisplayName("a new heap is empty")
    void aNewHeapIsEmpty() {
        assertThat(heap.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a heap holding one connection has it at the top")
    void oneConnectionIsAtTheTop() {
        final InFlightQueue<EventWindowInTransit> connection = connection(0, 10);

        heap.add(connection, 0);

        assertThat(heap.isEmpty()).isFalse();
        assertThat(heap.top()).isSameAs(connection);
    }

    @Test
    @DisplayName("connections come out in the order their heads arrive, whatever order they were added in")
    void connectionsAreOrderedByHeadArrival() {
        final InFlightQueue<EventWindowInTransit> late = connection(0, 30);
        final InFlightQueue<EventWindowInTransit> early = connection(1, 10);
        final InFlightQueue<EventWindowInTransit> middle = connection(2, 20);

        heap.add(late, 0);
        heap.add(early, 1);
        heap.add(middle, 2);

        assertThat(drain()).containsExactly(early, middle, late);
    }

    @Test
    @DisplayName("connections whose heads arrive at the same instant come out in sender rank order")
    void tiesAreBrokenBySenderRank() {
        // Added highest rank first, so insertion order and rank order disagree.
        final InFlightQueue<EventWindowInTransit> rankTwo = connection(2, 10);
        final InFlightQueue<EventWindowInTransit> rankOne = connection(1, 10);
        final InFlightQueue<EventWindowInTransit> rankZero = connection(0, 10);

        heap.add(rankTwo, 2);
        heap.add(rankOne, 1);
        heap.add(rankZero, 0);

        assertThat(drain()).containsExactly(rankZero, rankOne, rankTwo);
    }

    @Test
    @DisplayName("an earlier head comes first even when its sender has a higher rank")
    void arrivalTimeComesBeforeSenderRank() {
        final InFlightQueue<EventWindowInTransit> lowRankLate = connection(0, 20);
        final InFlightQueue<EventWindowInTransit> highRankEarly = connection(5, 10);

        heap.add(lowRankLate, 0);
        heap.add(highRankEarly, 5);

        assertThat(drain()).containsExactly(highRankEarly, lowRankLate);
    }

    @Test
    @DisplayName("reordering the top moves it below a connection whose head is now earlier")
    void reorderingTheTopMovesItDown() {
        final InFlightQueue<EventWindowInTransit> first = connection(0, 10, 30);
        final InFlightQueue<EventWindowInTransit> second = connection(1, 20);
        heap.add(first, 0);
        heap.add(second, 1);
        assertThat(heap.top()).isSameAs(first);

        first.poll();
        heap.reorderTop();

        assertThat(drain()).containsExactly(second, first);
    }

    @Test
    @DisplayName("reordering the top keeps it there when its new head is still the earliest")
    void reorderingTheTopCanKeepItThere() {
        final InFlightQueue<EventWindowInTransit> first = connection(0, 10, 15);
        final InFlightQueue<EventWindowInTransit> second = connection(1, 20);
        heap.add(first, 0);
        heap.add(second, 1);

        first.poll();
        heap.reorderTop();

        assertThat(heap.top()).isSameAs(first);
    }

    @Test
    @DisplayName("a reordered top whose new head ties with another connection goes after a lower sender rank")
    void reorderingTheTopBreaksTiesBySenderRank() {
        final InFlightQueue<EventWindowInTransit> rankTwo = connection(2, 10, 20);
        final InFlightQueue<EventWindowInTransit> rankOne = connection(1, 20);
        final InFlightQueue<EventWindowInTransit> rankThree = connection(3, 20);
        heap.add(rankTwo, 2);
        heap.add(rankOne, 1);
        heap.add(rankThree, 3);
        assertThat(heap.top()).isSameAs(rankTwo);

        rankTwo.poll();
        heap.reorderTop();

        assertThat(drain()).containsExactly(rankOne, rankTwo, rankThree);
    }

    @Test
    @DisplayName("removing the top of a heap holding one connection leaves it empty")
    void removingTheOnlyConnectionEmptiesTheHeap() {
        heap.add(connection(0, 10), 0);

        heap.removeTop();

        assertThat(heap.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("clearing removes every connection, and the heap can be used again afterwards")
    void clearingEmptiesTheHeapForReuse() {
        heap.add(connection(0, 10), 0);
        heap.add(connection(1, 20), 1);

        heap.clear();
        assertThat(heap.isEmpty()).isTrue();

        final InFlightQueue<EventWindowInTransit> late = connection(2, 40);
        final InFlightQueue<EventWindowInTransit> early = connection(3, 30);
        heap.add(late, 2);
        heap.add(early, 3);
        assertThat(drain()).containsExactly(early, late);
    }

    @Test
    @DisplayName("many connections come out in order, including ties, past the initial capacity")
    void manyConnectionsComeOutInOrder() {
        final Random random = new Random(0);
        final List<Ranked> added = new ArrayList<>();
        for (int rank = 0; rank < 100; rank++) {
            // few distinct arrival times, so that many heads tie and the rank has to decide
            final InFlightQueue<EventWindowInTransit> connection = connection(rank, random.nextInt(10));
            added.add(new Ranked(connection, rank));
        }
        final List<Ranked> shuffled = new ArrayList<>(added);
        Collections.shuffle(shuffled, random);
        for (final Ranked ranked : shuffled) {
            heap.add(ranked.connection(), ranked.rank());
        }

        final List<InFlightQueue<EventWindowInTransit>> expected = added.stream()
                .sorted(Comparator.comparingLong(
                                (Ranked ranked) -> ranked.connection().headArrival())
                        .thenComparingInt(Ranked::rank))
                .map(Ranked::connection)
                .toList();
        assertThat(drain()).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("repeatedly delivering from the top and reordering it merges all queues into arrival order")
    void reorderingTheTopMergesQueues() {
        final Random random = new Random(1);
        final List<Long> expectedArrivals = new ArrayList<>();
        for (int rank = 0; rank < 20; rank++) {
            final InFlightQueue<EventWindowInTransit> connection = new InFlightQueue<>(START);
            for (int i = 0; i < 10; i++) {
                final Instant arrival = START.plusMillis(random.nextInt(50));
                connection.add(item(rank, arrival));
                expectedArrivals.add(InFlightQueue.nanosSince(START, arrival));
            }
            heap.add(connection, rank);
        }
        expectedArrivals.sort(null);

        final List<Long> mergedArrivals = new ArrayList<>();
        while (!heap.isEmpty()) {
            final InFlightQueue<EventWindowInTransit> top = heap.top();
            mergedArrivals.add(top.headArrival());
            top.poll();
            if (top.peek() == null) {
                heap.removeTop();
            } else {
                heap.reorderTop();
            }
        }

        assertThat(mergedArrivals).containsExactlyElementsOf(expectedArrivals);
    }

    /**
     * A connection together with the sender rank it is added to the heap with.
     */
    private record Ranked(@NonNull InFlightQueue<EventWindowInTransit> connection, int rank) {}

    /**
     * Takes every connection out of the heap, top first.
     *
     * @return the connections in the order they came out
     */
    private List<InFlightQueue<EventWindowInTransit>> drain() {
        final List<InFlightQueue<EventWindowInTransit>> drained = new ArrayList<>();
        while (!heap.isEmpty()) {
            drained.add(heap.top());
            heap.removeTop();
        }
        return drained;
    }

    /**
     * Creates a connection holding items that arrive the given numbers of milliseconds after the start.
     *
     * @param sender        the node sending on the connection
     * @param arrivalMillis when each item arrives, in milliseconds after the start
     * @return the connection
     */
    private static InFlightQueue<EventWindowInTransit> connection(final int sender, final long... arrivalMillis) {
        final InFlightQueue<EventWindowInTransit> connection = new InFlightQueue<>(START);
        for (final long millis : arrivalMillis) {
            connection.add(item(sender, START.plusMillis(millis)));
        }
        return connection;
    }

    private static EventWindowInTransit item(final int sender, @NonNull final Instant arrival) {
        return new EventWindowInTransit(EventWindow.getGenesisEventWindow(), NodeId.of(sender), arrival);
    }
}
