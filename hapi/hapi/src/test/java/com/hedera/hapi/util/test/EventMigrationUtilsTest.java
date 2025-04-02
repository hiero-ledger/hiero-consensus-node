// SPDX-License-Identifier: Apache-2.0
package com.hedera.hapi.util.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.util.EventMigrationUtils;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventMigrationUtils}.
 */
public class EventMigrationUtilsTest {
    /** A descriptor instance that is put into {@link EventCore} */
    private static final EventDescriptor EVENT_CORE_PARENT_DESCRIPTOR =
            EventDescriptor.newBuilder().build();
    /** A descriptor instance that is put into {@link GossipEvent} */
    private static final EventDescriptor GOSSIP_EVENT_PARENT_DESCRIPTOR =
            EventDescriptor.newBuilder().build();
    /** An event whose parents are stored in {@link EventCore}, not in {@link GossipEvent} */
    private static final GossipEvent EVENT_CORE_PARENTS = GossipEvent.newBuilder()
            .eventCore(EventCore.newBuilder().parents(EVENT_CORE_PARENT_DESCRIPTOR))
            .build();
    /** An event whose parents are stored in {@link GossipEvent}, not in {@link EventCore} */
    private static final GossipEvent GOSSIP_EVENT_PARENTS = GossipEvent.newBuilder()
            .eventCore(EventCore.newBuilder().build())
            .parents(GOSSIP_EVENT_PARENT_DESCRIPTOR)
            .build();
    /** An event whose parents are stored both in {@link GossipEvent} and in {@link EventCore} */
    private static final GossipEvent BOTH_PLACES_PARENTS = GossipEvent.newBuilder()
            .eventCore(
                    EventCore.newBuilder().parents(EVENT_CORE_PARENT_DESCRIPTOR).build())
            .parents(GOSSIP_EVENT_PARENT_DESCRIPTOR)
            .build();

    /**
     * Tests the method that checks if parents are populated correctly.
     */
    @Test
    void parentsPopulatedCorrectlyTest() {
        assertTrue(EventMigrationUtils.areParentsPopulatedCorrectly(EVENT_CORE_PARENTS));
        assertTrue(EventMigrationUtils.areParentsPopulatedCorrectly(GOSSIP_EVENT_PARENTS));
        assertFalse(EventMigrationUtils.areParentsPopulatedCorrectly(BOTH_PLACES_PARENTS));
    }

    /**
     * Tests the method that gets the appropriate parents.
     */
    @Test
    void getParentsTest() {
        assertEquals(1, EventMigrationUtils.getParents(EVENT_CORE_PARENTS).size());
        assertEquals(1, EventMigrationUtils.getParents(GOSSIP_EVENT_PARENTS).size());

        assertSame(
                EVENT_CORE_PARENT_DESCRIPTOR,
                EventMigrationUtils.getParents(EVENT_CORE_PARENTS).getFirst());
        assertSame(
                GOSSIP_EVENT_PARENT_DESCRIPTOR,
                EventMigrationUtils.getParents(GOSSIP_EVENT_PARENTS).getFirst());
    }
}
