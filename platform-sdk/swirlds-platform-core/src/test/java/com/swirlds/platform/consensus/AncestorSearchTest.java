// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.graph.SimpleGraphs;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class AncestorSearchTest {

    final EventVisitedMark mark = new EventVisitedMark();
    final AncestorSearch search = new AncestorSearch(mark);
    final List<EventImpl> events = SimpleGraphs.graph9e3n(RandomUtils.getRandomPrintSeed());
    final EventImpl root = events.get(8);

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
                search.commonAncestorsOf(List.of(events.get(5), events.get(6), events.get(7)), e -> true);
        assertEquals(1, ancestors.size());
        assertSame(events.get(1), ancestors.get(0));
        final HashSet<Instant> recTimes = new HashSet<>(events.get(1).getRecTimes());
        assertEquals(3, recTimes.size());
        assertTrue(recTimes.contains(events.get(3).getTimeCreated()));
        assertTrue(recTimes.contains(events.get(6).getTimeCreated()));
        assertTrue(recTimes.contains(events.get(7).getTimeCreated()));

        IntStream.of(0, 2, 3, 4, 5, 6, 7, 8)
                .forEach(i -> assertNull(events.get(i).getRecTimes()));
        events.get(1).setRecTimes(null);
    }

    private void searchAndAssert() {
        // look for non-consensus ancestors of 8
        final Map<Hash, EventImpl> ancestors = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(search.initializeSearch(root, e -> !e.isConsensus()), 0),
                        false)
                .collect(Collectors.toMap(EventImpl::getBaseHash, e -> e));
        assertEquals(6, ancestors.size());
        IntStream.of(2, 3, 4, 6, 7, 8)
                .forEach(i -> assertTrue(ancestors.containsKey(events.get(i).getBaseHash())));
    }
}
