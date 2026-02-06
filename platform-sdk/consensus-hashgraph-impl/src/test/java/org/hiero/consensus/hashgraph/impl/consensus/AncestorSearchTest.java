// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Spliterators;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.test.fixtures.graph.SimpleGraph;
import org.hiero.consensus.hashgraph.impl.test.fixtures.graph.SimpleGraphs;
import org.hiero.consensus.hashgraph.impl.test.fixtures.graph.internal.SimpleEventImplGraph;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@link AncestorSearch}
 */
class AncestorSearchTest {
    /** A predicate that matches all events */
    private static final Predicate<EventImpl> ALL_EVENTS = e -> true;
    /** A predicate that matches only non-consensus events */
    private static final Predicate<EventImpl> NON_CONSENSUS_EVENTS = e -> !e.isConsensus();

    private final SimpleGraphs<EventImpl> graphs = new SimpleGraphs<>(SimpleEventImplGraph::new);

    /**
     * Tests the graph with multiple other-parents
     */
    @Test
    void mopGraph() {
        final SimpleGraph<EventImpl> graph = graphs.mopGraph(Randotron.create());
        final AncestorSearch search = new AncestorSearch();

        assertEquals(graph.hashes(1, 2, 3, 6), getAncestors(search, graph.event(6)));
        assertEquals(graph.hashes(0, 4, 8), getAncestors(search, graph.event(8)));
        assertEquals(graph.hashes(0, 1, 2, 3, 4, 5, 6, 9), getAncestors(search, graph.event(9)));
        assertEquals(graph.hashes(0, 1, 2, 3, 5, 6, 7, 10), getAncestors(search, graph.event(10)));
        assertEquals(graph.hashes(3, 7, 11), getAncestors(search, graph.event(11)));

        assertEquals(
                graph.hashes(0, 1, 2, 3, 5, 6),
                search.commonAncestorsOf(graph.events(9, 10), ALL_EVENTS).stream()
                        .map(EventImpl::getBaseHash)
                        .collect(Collectors.toSet()));
        // clear the recTimes so they don't interfere with the next search
        graph.events().forEach(e -> e.setRecTimes(null));

        assertEquals(
                graph.hashes(0),
                search.commonAncestorsOf(graph.events(8, 9, 10), ALL_EVENTS).stream()
                        .map(EventImpl::getBaseHash)
                        .collect(Collectors.toSet()));
    }

    /**
     * Tests the graph with 9 events and 3 nodes. This test is parameterized to run with different starting marks to
     * ensure that the marking system works correctly. It also validates that the recTimes are set correctly on the
     * common ancestor.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, -1, Integer.MAX_VALUE})
    void graph9e3n(final int startingMark) {
        final EventVisitedMark mark = new EventVisitedMark();
        final AncestorSearch search = new AncestorSearch(mark);
        final SimpleGraph<EventImpl> graph = graphs.graph9e3n(Randotron.create());

        // we test various starting marks to ensure that the marking system works correctly
        mark.setMark(startingMark);

        // test getting ancestors of the latest event(8)
        assertEquals(graph.hashes(2, 3, 4, 6, 7, 8), getAncestors(search, graph.event(8), NON_CONSENSUS_EVENTS));

        // test getting common ancestors of events 5,6 & 7
        final List<EventImpl> ancestors = search.commonAncestorsOf(graph.events(5, 6, 7), ALL_EVENTS);
        // we expect only one common ancestor: event 1
        assertEquals(1, ancestors.size());
        assertSame(graph.event(1), ancestors.getFirst());

        // verify that recTimes are correct
        final EventImpl commonAncestor = ancestors.getFirst();
        assertNotNull(commonAncestor.getRecTimes());
        final HashSet<Instant> recTimes = new HashSet<>(commonAncestor.getRecTimes());
        assertEquals(3, recTimes.size());
        assertTrue(recTimes.contains(graph.event(3).getTimeCreated()));
        assertTrue(recTimes.contains(graph.event(6).getTimeCreated()));
        assertTrue(recTimes.contains(graph.event(7).getTimeCreated()));

        // verify that other events' recTimes are still null
        graph.events(0, 2, 3, 4, 5, 6, 7, 8).stream()
                .map(EventImpl::getRecTimes)
                .forEach(Assertions::assertNull);
    }

    /**
     * Same as {@link #getAncestors(AncestorSearch, EventImpl, Predicate)} with a predicate that matches all events
     */
    private Set<Hash> getAncestors(@NonNull final AncestorSearch search, @NonNull final EventImpl event) {
        return getAncestors(search, event, ALL_EVENTS);
    }

    /**
     * Get the ancestors of an event that match the given predicate
     * @param search the ancestor search instance to use
     * @param event the event whose ancestors to find
     * @param predicate the predicate to filter ancestors
     * @return the set of ancestor hashes that match the predicate
     */
    private Set<Hash> getAncestors(
            @NonNull final AncestorSearch search,
            @NonNull final EventImpl event,
            @NonNull final Predicate<EventImpl> predicate) {
        final AncestorIterator ancestorIterator = search.initializeSearch(event, predicate);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(ancestorIterator, 0), false)
                .map(EventImpl::getBaseHash)
                .collect(Collectors.toSet());
    }
}
