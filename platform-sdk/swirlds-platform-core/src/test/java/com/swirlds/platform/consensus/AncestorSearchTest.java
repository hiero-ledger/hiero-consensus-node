// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.graph.SimpleGraph;
import com.swirlds.platform.test.fixtures.graph.SimpleGraphs;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class AncestorSearchTest {

    final EventVisitedMark mark = new EventVisitedMark();
    final AncestorSearch search = new AncestorSearch(mark);
    final SimpleGraph graph = new SimpleGraph(SimpleGraphs.graph9e3n(RandomUtils.getRandomPrintSeed()));
    final EventImpl root = graph.getImpl(8);

    @RepeatedTest(3)
    void basicTest() {
        searchAndAssert();
    }

    @Test
    void markWraparound() {
        mark.setMark(-1);
        searchAndAssert();
    }

    @Test
    void markOverflow() {
        mark.setMark(Integer.MAX_VALUE);
        searchAndAssert();
    }

    @Test
    void commonAncestors() {
        final List<EventImpl> ancestors =
                search.commonAncestorsOf(graph.getImpls(5,6,7), e -> true);
        assertEquals(1, ancestors.size());
        assertSame(graph.getImpl(1), ancestors.get(0));
        assertNotNull(graph.getImpl(1).getRecTimes());
        final HashSet<Instant> recTimes = new HashSet<>(graph.getImpl(1).getRecTimes());
        assertEquals(3, recTimes.size());
        assertTrue(recTimes.contains(graph.getImpl(3).getTimeCreated()));
        assertTrue(recTimes.contains(graph.getImpl(6).getTimeCreated()));
        assertTrue(recTimes.contains(graph.getImpl(7).getTimeCreated()));

        graph.getImpls(0, 2, 3, 4, 5, 6, 7, 8)
                .stream()
                .map(EventImpl::getRecTimes)
                .forEach(Assertions::assertNull);
        graph.getImpl(1).setRecTimes(null);
    }

    private void searchAndAssert() {
        // look for non-consensus ancestors of 8
        final AncestorIterator ancestorIterator = search.initializeSearch(root, e -> !e.isConsensus());
        final Map<Hash, EventImpl> ancestors = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(ancestorIterator, 0),
                        false)
                .collect(Collectors.toMap(EventImpl::getBaseHash, e -> e));
        assertEquals(6, ancestors.size());
        graph.getImpls(2, 3, 4, 6, 7, 8)
                .stream().map(EventImpl::getBaseHash)
                .forEach(e -> assertTrue(ancestors.containsKey(e)));
    }
}
