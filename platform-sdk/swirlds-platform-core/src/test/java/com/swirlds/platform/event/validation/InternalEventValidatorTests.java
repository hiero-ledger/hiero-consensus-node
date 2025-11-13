// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.validation;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.gossip.IntakeEventCounter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.SignatureType;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.transaction.TransactionLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for {@link DefaultInternalEventValidator}
 */
class InternalEventValidatorTests {

    private static final long SINGLE_NODE_ROUND = 1L;
    private static final long MULTI_NODE_ROUND = 2L;
    private static final TransactionLimits TRANSACTION_LIMITS = new TransactionLimits(133120, 245760);

    private AtomicLong exitedIntakePipelineCount;
    private Random random;
    private InternalEventValidator validator;

    @BeforeEach
    void setup() {
        random = getRandomPrintSeed();

        exitedIntakePipelineCount = new AtomicLong(0);
        final IntakeEventCounter intakeEventCounter = mock(IntakeEventCounter.class);
        doAnswer(invocation -> {
                    exitedIntakePipelineCount.incrementAndGet();
                    return null;
                })
                .when(intakeEventCounter)
                .eventExitedIntakePipeline(any());

        final Time time = new FakeTime();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().withTime(time).build();

        final RosterHistory rosterHistory = createRosterHistory();

        validator = new DefaultInternalEventValidator(
                platformContext, rosterHistory, intakeEventCounter, TRANSACTION_LIMITS);
    }

    /**
     * Creates a mocked roster history where round 1 has a single node roster and round 2+ has a multi-node roster.
     *
     * @return the mocked roster history
     */
    private static RosterHistory createRosterHistory() {
        final Roster singleNodeRoster = mock(Roster.class);
        when(singleNodeRoster.rosterEntries()).thenReturn(List.of(mock(RosterEntry.class)));

        final Roster multiNodeRoster = mock(Roster.class);
        when(multiNodeRoster.rosterEntries()).thenReturn(List.of(mock(RosterEntry.class), mock(RosterEntry.class)));

        final RosterHistory rosterHistory = mock(RosterHistory.class);
        when(rosterHistory.getRosterForRound(SINGLE_NODE_ROUND)).thenReturn(singleNodeRoster);
        when(rosterHistory.getRosterForRound(Mockito.longThat(round -> round >= MULTI_NODE_ROUND)))
                .thenReturn(multiNodeRoster);

        return rosterHistory;
    }

    @Test
    @DisplayName("An event with null fields is invalid")
    void nullFields() {
        final PlatformEvent platformEvent = Mockito.mock(PlatformEvent.class);

        final Randotron r = Randotron.create();
        final GossipEvent wholeEvent = new TestingEventBuilder(r)
                .setSystemTransactionCount(1)
                .setAppTransactionCount(2)
                .setSelfParent(new TestingEventBuilder(r).build())
                .setOtherParent(new TestingEventBuilder(r).build())
                .build()
                .getGossipEvent();

        final GossipEvent noEventCore = GossipEvent.newBuilder()
                .eventCore((EventCore) null)
                .signature(wholeEvent.signature())
                .transactions(wholeEvent.transactions())
                .build();
        when(platformEvent.getGossipEvent()).thenReturn(noEventCore);
        assertNull(validator.validateEvent(platformEvent));
        assertEquals(1, exitedIntakePipelineCount.get());

        final GossipEvent noTimeCreated = GossipEvent.newBuilder()
                .eventCore(EventCore.newBuilder()
                        .timeCreated((Timestamp) null)
                        .birthRound(MULTI_NODE_ROUND)
                        .build())
                .signature(wholeEvent.signature())
                .transactions(wholeEvent.transactions())
                .build();
        when(platformEvent.getGossipEvent()).thenReturn(noTimeCreated);
        assertNull(validator.validateEvent(platformEvent));
        assertEquals(2, exitedIntakePipelineCount.get());

        final GossipEvent nullTransaction = GossipEvent.newBuilder()
                .eventCore(wholeEvent.eventCore())
                .signature(wholeEvent.signature())
                .transactions(List.of(Bytes.EMPTY))
                .build();
        when(platformEvent.getGossipEvent()).thenReturn(nullTransaction);

        assertNull(validator.validateEvent(platformEvent));
        assertEquals(3, exitedIntakePipelineCount.get());

        final ArrayList<EventDescriptor> parents = new ArrayList<>();
        parents.add(null);
        final GossipEvent nullParent = GossipEvent.newBuilder()
                .eventCore(wholeEvent.eventCore())
                .signature(wholeEvent.signature())
                .transactions(wholeEvent.transactions())
                .parents(parents)
                .build();
        when(platformEvent.getGossipEvent()).thenReturn(nullParent);
        assertNull(validator.validateEvent(platformEvent));
        assertEquals(4, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("An event with a byte field length that is invalid")
    void byteFieldLength() {
        final PlatformEvent platformEvent = Mockito.mock(PlatformEvent.class);
        final Randotron r = Randotron.create();
        final GossipEvent validEvent = new TestingEventBuilder(r)
                .setSystemTransactionCount(1)
                .setAppTransactionCount(2)
                .setSelfParent(new TestingEventBuilder(r)
                        .setBirthRound(MULTI_NODE_ROUND)
                        .build())
                .setOtherParent(new TestingEventBuilder(r)
                        .setBirthRound(MULTI_NODE_ROUND)
                        .build())
                .build()
                .getGossipEvent();

        final GossipEvent shortSignature = GossipEvent.newBuilder()
                .eventCore(validEvent.eventCore())
                .signature(validEvent.signature().getBytes(1, SignatureType.RSA.signatureLength() - 2))
                .transactions(validEvent.transactions())
                .build();
        when(platformEvent.getGossipEvent()).thenReturn(shortSignature);
        assertNull(validator.validateEvent(platformEvent));
        assertEquals(1, exitedIntakePipelineCount.get());

        final GossipEvent shortDescriptorHash = GossipEvent.newBuilder()
                .eventCore(validEvent.eventCore())
                .signature(validEvent.signature())
                .transactions(validEvent.transactions())
                .parents(EventDescriptor.newBuilder()
                        .hash(validEvent.parents().getFirst().hash().getBytes(1, DigestType.SHA_384.digestLength() - 2))
                        .build())
                .build();
        when(platformEvent.getGossipEvent()).thenReturn(shortDescriptorHash);
        assertNull(validator.validateEvent(platformEvent));
        assertEquals(2, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("An event with too many transaction bytes is invalid")
    void tooManyTransactionBytes() {
        // default max is 245_760 bytes
        final PlatformEvent event = new TestingEventBuilder(random)
                .setTransactionSize(100)
                .setAppTransactionCount(5000)
                .setSystemTransactionCount(0)
                .build();

        assertNull(validator.validateEvent(event));

        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("An event with identical parents is only valid in a single node network")
    void identicalParents() {
        final PlatformEvent parent =
                new TestingEventBuilder(random).setBirthRound(SINGLE_NODE_ROUND).build();
        final PlatformEvent validSingeNodeNetworkEvent = new TestingEventBuilder(random)
                .setSelfParent(parent)
                .setOtherParent(parent)
                .setBirthRound(SINGLE_NODE_ROUND)
                .build();
        final PlatformEvent invalidSingeNodeNetworkEvent = new TestingEventBuilder(random)
                .setSelfParent(parent)
                .setOtherParent(parent)
                .setBirthRound(MULTI_NODE_ROUND)
                .build();

        assertNull(validator.validateEvent(invalidSingeNodeNetworkEvent));
        assertEquals(validSingeNodeNetworkEvent, validator.validateEvent(validSingeNodeNetworkEvent));

        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("An event must have a birth round greater than or equal to the max of all parent birth rounds.")
    void invalidBirthRound() {
        final PlatformEvent selfParent1 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(0))
                .setBirthRound(5)
                .build();
        final PlatformEvent otherParent1 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(1))
                .setBirthRound(7)
                .build();
        final PlatformEvent selfParent2 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(0))
                .setBirthRound(7)
                .build();
        final PlatformEvent otherParent2 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(1))
                .setBirthRound(5)
                .build();

        assertNull(validator.validateEvent(new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(0))
                .setSelfParent(selfParent1)
                .setOtherParent(otherParent1)
                .setBirthRound(6)
                .build()));
        assertNull(validator.validateEvent(new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(0))
                .setSelfParent(selfParent2)
                .setOtherParent(otherParent2)
                .setBirthRound(6)
                .build()));
        assertNull(validator.validateEvent(new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(0))
                .setSelfParent(selfParent1)
                .setOtherParent(otherParent1)
                .setBirthRound(4)
                .build()));
        assertNull(validator.validateEvent(new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(0))
                .setSelfParent(selfParent2)
                .setOtherParent(otherParent2)
                .setBirthRound(4)
                .build()));
        assertNotNull(validator.validateEvent(new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(0))
                .setSelfParent(selfParent1)
                .setOtherParent(otherParent1)
                .setBirthRound(7)
                .build()));
        assertNotNull(validator.validateEvent(new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(0))
                .setSelfParent(selfParent2)
                .setOtherParent(otherParent2)
                .setBirthRound(7)
                .build()));

        assertEquals(4, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Test that an event with no issues passes validation")
    void successfulValidation() {
        final PlatformEvent normalEvent = new TestingEventBuilder(random)
                .setSelfParent(new TestingEventBuilder(random)
                        .setBirthRound(MULTI_NODE_ROUND)
                        .build())
                .setOtherParent(new TestingEventBuilder(random)
                        .setBirthRound(MULTI_NODE_ROUND)
                        .build())
                .build();
        final PlatformEvent missingSelfParent = new TestingEventBuilder(random)
                .setSelfParent(null)
                .setOtherParent(new TestingEventBuilder(random)
                        .setBirthRound(MULTI_NODE_ROUND)
                        .build())
                .build();

        final PlatformEvent missingOtherParent = new TestingEventBuilder(random)
                .setSelfParent(new TestingEventBuilder(random)
                        .setBirthRound(MULTI_NODE_ROUND)
                        .build())
                .setOtherParent(null)
                .build();

        assertEquals(normalEvent, validator.validateEvent(normalEvent));
        assertEquals(missingSelfParent, validator.validateEvent(missingSelfParent));
        assertEquals(missingOtherParent, validator.validateEvent(missingOtherParent));

        assertEquals(0, exitedIntakePipelineCount.get());
    }
}
