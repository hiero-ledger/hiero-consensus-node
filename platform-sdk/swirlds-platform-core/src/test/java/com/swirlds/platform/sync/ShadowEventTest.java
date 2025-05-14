// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.sync;

import static com.swirlds.platform.test.fixtures.sync.EventEquality.identicalHashes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.gossip.shadowgraph.ShadowEvent;
import java.util.Random;
import org.hiero.base.utility.test.fixtures.RandomUtils;
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

        final ShadowEvent s = new ShadowEvent(e, ssp, sop);

        assertTrue(
                identicalHashes(
                        s.getSelfParent().getEventBaseHash(), ssp.getEvent().getHash()),
                "expected SP");
        assertTrue(
                identicalHashes(
                        s.getOtherParent().getEvent().getHash(), sop.getEvent().getHash()),
                "expected OP");

        assertSame(s.getEvent(), e, "getting the EventImpl should give the EventImpl instance itself");
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

        final ShadowEvent s = new ShadowEvent(e, ssp, sop);

        assertNotNull(s.getSelfParent(), "SP should not be null before disconnect");

        assertNotNull(s.getOtherParent(), "OP should not be null before disconnect");

        s.disconnect();

        assertNull(s.getSelfParent(), "SP should be null after disconnect");

        assertNull(s.getOtherParent(), "OP should be null after disconnect");
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
        final ShadowEvent s = new ShadowEvent(e, ssp, sop);

        // The hash of an event Shadow is the hash of the event
        assertEquals(e.getHash(), s.getEventBaseHash(), "false");
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
        final ShadowEvent s = new ShadowEvent(e, ssp, sop);

        testLinkedConstruction(s, ssp, sop);
    }

    private static void testLinkedConstruction(final ShadowEvent s, final ShadowEvent ssp, final ShadowEvent sop) {
        testSP(s, ssp);
        testOP(s, sop);
    }

    @Test
    @DisplayName("no links when constructed without other events")
    void testUnlinkedConstruction() {
        final PlatformEvent e = builder.setSelfParent(null).setOtherParent(null).build();
        final ShadowEvent s = new ShadowEvent(e);

        testUnlinkedConstruction(s);
    }

    private static void testUnlinkedConstruction(final ShadowEvent s) {
        assertNull(s.getSelfParent(), "");
        assertNull(s.getOtherParent(), "");
    }

    private static void testSP(final ShadowEvent s, final ShadowEvent ssp) {
        assertEquals(ssp, s.getSelfParent(), "expect given SP == SP of shadow event");
    }

    private static void testOP(final ShadowEvent s, final ShadowEvent sop) {
        assertEquals(sop, s.getOtherParent(), "expect given OP == OP of shadow event");
    }
}
