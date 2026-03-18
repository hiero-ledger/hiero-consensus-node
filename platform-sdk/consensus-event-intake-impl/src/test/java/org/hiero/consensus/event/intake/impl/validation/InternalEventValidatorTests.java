// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl.validation;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.base.crypto.DigestType;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.transaction.TransactionLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for {@link DefaultInternalEventValidator}
 */
class InternalEventValidatorTests {

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

        validator = new DefaultInternalEventValidator(
                new NoOpMetrics(), new FakeTime(), intakeEventCounter, TRANSACTION_LIMITS);
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
                .eventCore(EventCore.newBuilder().timeCreated((Timestamp) null).build())
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
                .setSelfParent(new TestingEventBuilder(r).build())
                .setOtherParent(new TestingEventBuilder(r).build())
                .build()
                .getGossipEvent();

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
        assertEquals(1, exitedIntakePipelineCount.get());
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
    @DisplayName("An event with duplicate parents is invalid")
    void duplicateParents() {
        final PlatformEvent parent = new TestingEventBuilder(random).build();
        final PlatformEvent invalidEvent = new TestingEventBuilder(random)
                .setSelfParent(parent)
                .setOtherParent(parent)
                .build();

        assertNull(validator.validateEvent(invalidEvent));
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
                        .setCreatorId(NodeId.of(0))
                        .build())
                .setOtherParent(new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(1))
                        .build())
                .build();
        final PlatformEvent missingSelfParent = new TestingEventBuilder(random)
                .setSelfParent(null)
                .setOtherParent(new TestingEventBuilder(random).build())
                .build();

        final PlatformEvent missingOtherParent = new TestingEventBuilder(random)
                .setSelfParent(new TestingEventBuilder(random).build())
                .setOtherParent(null)
                .build();

        assertEquals(normalEvent, validator.validateEvent(normalEvent));
        assertEquals(missingSelfParent, validator.validateEvent(missingSelfParent));
        assertEquals(missingOtherParent, validator.validateEvent(missingOtherParent));

        assertEquals(0, exitedIntakePipelineCount.get());
    }
}
