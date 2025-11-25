// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.graph.SimpleGraph;
import com.swirlds.platform.test.fixtures.graph.SimpleGraphs;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Spliterators;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AncestorSearchTest {
    private static final Predicate<EventImpl> ALL_EVENTS = e -> true;
    private static final Predicate<EventImpl> NON_CONSENSUS_EVENTS = e -> !e.isConsensus();

    @Test
    void mopGraph(){
        final SimpleGraph graph = SimpleGraphs.mopGraph(Randotron.create());
        final AncestorSearch search = new AncestorSearch();

        assertEquals(
                graph.hashes(1,2,3,6),
                getAncestors(search, graph.impl(6)));
        assertEquals(
                graph.hashes(0,4,8),
                getAncestors(search, graph.impl(8)));
        assertEquals(
                graph.hashes(0, 1, 2, 3, 4, 5, 6, 9),
                getAncestors(search, graph.impl(9)));
        assertEquals(
                graph.hashes(0, 1, 2, 3, 5, 6, 7, 10),
                getAncestors(search, graph.impl(10)));
        assertEquals(
                graph.hashes(3,7,11),
                getAncestors(search, graph.impl(11)));

        assertEquals(
                graph.hashes(0,1,2,3,5,6),
                search.commonAncestorsOf(graph.impls(9,10), ALL_EVENTS)
                        .stream()
                        .map(EventImpl::getBaseHash)
                        .collect(Collectors.toSet()));
        // clear the recTimes so they don't interfere with the next search
        graph.impls().forEach(e -> e.setRecTimes(null));

        assertEquals(
                graph.hashes(0),
                search.commonAncestorsOf(graph.impls(8,9,10), ALL_EVENTS)
                        .stream()
                        .map(EventImpl::getBaseHash)
                        .collect(Collectors.toSet()));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, -1, Integer.MAX_VALUE})
    void graph9e3n(final int startingMark) {
        final EventVisitedMark mark = new EventVisitedMark();
        final AncestorSearch search = new AncestorSearch(mark);
        final SimpleGraph graph = SimpleGraphs.graph9e3n(Randotron.create());

        // we test various starting marks to ensure that the marking system works correctly
        mark.setMark(startingMark);

        // test getting ancestors of the latest event(8)
        assertEquals(
                graph.hashes(2, 3, 4, 6, 7, 8),
                getAncestors(search, graph.impl(8), NON_CONSENSUS_EVENTS));

        // test getting common ancestors of events 5,6 & 7
        final List<EventImpl> ancestors =
                search.commonAncestorsOf(graph.impls(5,6,7), ALL_EVENTS);
        // we expect only one common ancestor: event 1
        assertEquals(1, ancestors.size());
        assertSame(graph.impl(1), ancestors.getFirst());

        // verify that recTimes are correct
        final EventImpl commonAncestor = ancestors.getFirst();
        assertNotNull(commonAncestor.getRecTimes());
        final HashSet<Instant> recTimes = new HashSet<>(commonAncestor.getRecTimes());
        assertEquals(3, recTimes.size());
        assertTrue(recTimes.contains(graph.impl(3).getTimeCreated()));
        assertTrue(recTimes.contains(graph.impl(6).getTimeCreated()));
        assertTrue(recTimes.contains(graph.impl(7).getTimeCreated()));

        // verify that other events' recTimes are still null
        graph.impls(0, 2, 3, 4, 5, 6, 7, 8)
                .stream()
                .map(EventImpl::getRecTimes)
                .forEach(Assertions::assertNull);
    }

    private Set<Hash> getAncestors(final AncestorSearch search, final EventImpl event) {
        return getAncestors(search, event, ALL_EVENTS);
    }

    private Set<Hash> getAncestors(final AncestorSearch search, final EventImpl event, final Predicate<EventImpl> predicate) {
        final AncestorIterator ancestorIterator = search.initializeSearch(event, predicate);
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(ancestorIterator, 0),
                        false)
                .map(EventImpl::getBaseHash)
                .collect(Collectors.toSet());
    }
}
