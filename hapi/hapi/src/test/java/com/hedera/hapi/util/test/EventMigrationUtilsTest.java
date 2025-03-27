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

public class EventMigrationUtilsTest {
    private static final EventDescriptor OLD_PARENT_DESCRIPTOR =
            EventDescriptor.newBuilder().build();
    private static final EventDescriptor NEW_PARENT_DESCRIPTOR =
            EventDescriptor.newBuilder().build();
    private static final GossipEvent OLD_PARENTS = GossipEvent.newBuilder()
            .eventCore(EventCore.newBuilder().parents(OLD_PARENT_DESCRIPTOR))
            .build();
    private static final GossipEvent NEW_PARENTS = GossipEvent.newBuilder()
            .eventCore(EventCore.newBuilder().build())
            .parents(NEW_PARENT_DESCRIPTOR)
            .build();

    private static final GossipEvent BOTH_PARENTS = GossipEvent.newBuilder()
            .eventCore(EventCore.newBuilder().parents(OLD_PARENT_DESCRIPTOR).build())
            .parents(NEW_PARENT_DESCRIPTOR)
            .build();

    @Test
    void parentsPopulatedCorrectlyTest() {
        assertTrue(EventMigrationUtils.areParentsPopulatedCorrectly(OLD_PARENTS));
        assertTrue(EventMigrationUtils.areParentsPopulatedCorrectly(NEW_PARENTS));
        assertFalse(EventMigrationUtils.areParentsPopulatedCorrectly(BOTH_PARENTS));
    }

    @Test
    void getParentsTest() {
        assertEquals(1, EventMigrationUtils.getParents(OLD_PARENTS).size());
        assertEquals(1, EventMigrationUtils.getParents(NEW_PARENTS).size());

        assertSame(
                OLD_PARENT_DESCRIPTOR,
                EventMigrationUtils.getParents(OLD_PARENTS).getFirst());
        assertSame(
                NEW_PARENT_DESCRIPTOR,
                EventMigrationUtils.getParents(NEW_PARENTS).getFirst());
    }
}
