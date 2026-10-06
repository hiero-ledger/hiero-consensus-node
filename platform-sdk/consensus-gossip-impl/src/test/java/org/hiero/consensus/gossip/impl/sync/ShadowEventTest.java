// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.sync;

import static org.hiero.consensus.gossip.impl.test.fixtures.sync.EventEquality.identicalHashes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.ShadowEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("shadow event tests")
class ShadowEventTest {
    private TestingEventBuilder builder;

    @BeforeEach
    void setUp() {
        final Random random = RandomUtils.getRandomPrintSeed();
        builder = new TestingEventBuilder(random);
    }

    @Test
    @DisplayName("equals")
    void testEquals() {
        final PlatformEvent e0 =
                builder.setSelfParent(null).setOtherParent(null).build();
        final PlatformEvent e1 =
                builder.setSelfParent(null).setOtherParent(null).build();

        final ShadowEvent s0 = new ShadowEvent(e0);
        final ShadowEvent s1 = new ShadowEvent(e1);

        assertEquals(s0, s0, "Every shadow event compares equal to itself");

        assertNotEquals(s0, s1, "two shadow events with different event hashes must not compare equal");

        assertNotEquals(
                s0,
                new Object(),
                "A shadow event instance must not compare to equal to an instance of a non-derived type");

        assertNotNull(s0, "A shadow event instance must not compare to equal to null");
    }

    @Test
    @DisplayName("parent and children getters")
    void testGetters() {
        final PlatformEvent e = builder.setSelfParent(null).setOtherParent(null).build();
        final PlatformEvent esp =
                builder.setSelfParent(null).setOtherParent(null).build();
        final PlatformEvent eop =
                builder.setSelfParent(null).setOtherParent(null).build();

        final ShadowEvent ssp = new ShadowEvent(esp);
        final ShadowEvent sop = new ShadowEvent(eop);

        final ShadowEvent s = new ShadowEvent(e, List.of(ssp, sop));

        assertTrue(
                identicalHashes(
                        s.getSelfParent().getBaseHash(), ssp.getPlatformEvent().getHash()),
                "expected SP");
        assertTrue(
                identicalHashes(
                        s.getOtherParents().getFirst().getPlatformEvent().getHash(),
                        sop.getPlatformEvent().getHash()),
                "expected OP");

        assertSame(s.getPlatformEvent(), e, "getting the EventImpl should give the EventImpl instance itself");
    }

    @Test
    @DisplayName("disconnect an event")
    void testDisconnect() {
        final PlatformEvent e = builder.setSelfParent(null).setOtherParent(null).build();
        final PlatformEvent esp =
                builder.setSelfParent(null).setOtherParent(null).build();
        final PlatformEvent eop =
                builder.setSelfParent(null).setOtherParent(null).build();

        final ShadowEvent ssp = new ShadowEvent(esp);
        final ShadowEvent sop = new ShadowEvent(eop);

        final ShadowEvent s = new ShadowEvent(e, List.of(ssp, sop));

        assertNotNull(s.getSelfParent(), "SP should not be null before disconnect");

        assertNotEquals(0, s.getOtherParents().size(), "OP should not be null before disconnect");

        s.clear();

        assertNull(s.getSelfParent(), "SP should be null after disconnect");

        assertEquals(0, s.getOtherParents().size(), "OP should be null after disconnect");
    }

    @Test
    @DisplayName("the hash of a shadow event is the hash of the referenced hashgraph event")
    void testHash() {
        final PlatformEvent e = builder.setSelfParent(null).setOtherParent(null).build();
        final PlatformEvent esp =
                builder.setSelfParent(null).setOtherParent(null).build();
        final PlatformEvent eop =
                builder.setSelfParent(null).setOtherParent(null).build();

        // Parents, unlinked
        final ShadowEvent ssp = new ShadowEvent(esp);
        final ShadowEvent sop = new ShadowEvent(eop);

        // The shadow event, linked
        final ShadowEvent s = new ShadowEvent(e, List.of(ssp, sop));

        // The hash of an event Shadow is the hash of the event
        assertEquals(e.getHash(), s.getBaseHash(), "false");
    }

    @Test
    @DisplayName("parents linked by construction")
    void testLinkedConstruction() {
        final PlatformEvent e = builder.setSelfParent(null).setOtherParent(null).build();
        final PlatformEvent esp =
                builder.setSelfParent(null).setOtherParent(null).build();
        final PlatformEvent eop =
                builder.setSelfParent(null).setOtherParent(null).build();

        // Parents, unlinked
        final ShadowEvent ssp = new ShadowEvent(esp);
        final ShadowEvent sop = new ShadowEvent(eop);

        // The shadow event, linked
        final List<ShadowEvent> parentShadows = List.of(ssp, sop);
        final ShadowEvent s = new ShadowEvent(e, parentShadows);

        assertEquals(parentShadows, s.getAllParents(), "expect parent shadows to match");
    }

    @Test
    @DisplayName("no links when constructed without other events")
    void testUnlinkedConstruction() {
        final PlatformEvent e = builder.setSelfParent(null).setOtherParent(null).build();
        final ShadowEvent s = new ShadowEvent(e);

        assertNull(s.getSelfParent(), "");
        assertEquals(0, s.getOtherParents().size(), "");
    }
}
