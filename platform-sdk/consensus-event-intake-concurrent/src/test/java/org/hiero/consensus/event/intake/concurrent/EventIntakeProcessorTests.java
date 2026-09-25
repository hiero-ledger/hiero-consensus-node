// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.concurrent;

import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory.createRosterWrapper;
import static org.hiero.consensus.model.test.fixtures.roster.RosterWrapperHistoryFactory.createRosterWrapperHistory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.metrics.api.Metrics;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.hiero.base.crypto.BytesSignatureVerifier;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.validation.EventFieldValidator;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.roster.RosterWrapperHistory;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.test.fixtures.crypto.PreGeneratedX509Certs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventIntakeProcessorTests {

    private static final int PREVIOUS_ROSTER_ROUND = 2;
    private static final int ACTIVE_ROSTER_ROUND = 3;
    private static final NodeId PREVIOUS_ROSTER_NODE_ID = NodeId.of(66);
    private static final NodeId ACTIVE_ROSTER_NODE_ID = NodeId.of(77);

    private Randotron random;
    private Metrics metrics;
    private FakeTime time;
    private AtomicLong exitedIntakePipelineCount;
    private IntakeEventCounter intakeEventCounter;
    private RosterWrapperHistory rosterHistory;

    private final Function<PublicKey, BytesSignatureVerifier> trueVerifierFactory =
            publicKey -> (data, signature) -> true;

    private final Function<PublicKey, BytesSignatureVerifier> falseVerifierFactory =
            publicKey -> (data, signature) -> false;

    /** A field validator that always passes. */
    private final EventFieldValidator passingValidator = event -> true;

    /** A field validator that always fails. */
    private final EventFieldValidator failingValidator = event -> false;

    private final EventHasher eventHasher = new DefaultEventHasher();

    private EventIntakeProcessor processorWithTrueVerifier;
    private EventIntakeProcessor processorWithFalseVerifier;

    private static RosterEntry generateMockRosterEntry(final NodeId nodeId) {
        try {
            return new RosterEntry(
                    nodeId.id(),
                    10,
                    Bytes.wrap(PreGeneratedX509Certs.getSigCert(nodeId.id()).getEncoded()),
                    List.of());
        } catch (final CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setup() {
        random = Randotron.create();
        metrics = new NoOpMetrics();
        time = new FakeTime();

        exitedIntakePipelineCount = new AtomicLong(0);
        intakeEventCounter = mock(IntakeEventCounter.class);
        doAnswer(invocation -> {
                    exitedIntakePipelineCount.incrementAndGet();
                    return null;
                })
                .when(intakeEventCounter)
                .eventExitedIntakePipeline(any());

        rosterHistory = buildRosterHistory(
                PREVIOUS_ROSTER_ROUND, ACTIVE_ROSTER_ROUND, EventIntakeProcessorTests::generateMockRosterEntry);

        processorWithTrueVerifier = new ConcurrentEventIntakeProcessor(
                metrics,
                time,
                eventHasher,
                passingValidator,
                trueVerifierFactory,
                rosterHistory,
                intakeEventCounter,
                null);

        processorWithFalseVerifier = new ConcurrentEventIntakeProcessor(
                metrics,
                time,
                eventHasher,
                passingValidator,
                falseVerifierFactory,
                rosterHistory,
                intakeEventCounter,
                null);
    }

    private RosterWrapperHistory buildRosterHistory(
            final long previousRound, final long round, final Function<NodeId, RosterEntry> rosterEntryGenerator) {
        final RosterEntry previousNodeRosterEntry = rosterEntryGenerator.apply(PREVIOUS_ROSTER_NODE_ID);
        final RosterEntry activeNodeRosterEntry = rosterEntryGenerator.apply(ACTIVE_ROSTER_NODE_ID);

        final RosterWrapper previousRoster = createRosterWrapper(previousNodeRosterEntry);
        final RosterWrapper activeRoster = createRosterWrapper(activeNodeRosterEntry);

        return createRosterWrapperHistory(
                round, activeRoster,
                previousRound, previousRoster);
    }

    @Test
    @DisplayName("Valid gossip event passes all stages")
    void validGossipEvent() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        assertNotNull(processorWithTrueVerifier.processHashedEvent(event));
        assertEquals(0, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Ancient events are discarded before any other work")
    void ancientEventDiscarded() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        processorWithTrueVerifier.setEventWindow(
                EventWindowBuilder.builder().setAncientThreshold(100).build());

        assertNull(processorWithTrueVerifier.processHashedEvent(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Event failing field validation is discarded")
    void validationFailure() {
        final EventIntakeProcessor processor = new ConcurrentEventIntakeProcessor(
                metrics,
                time,
                eventHasher,
                failingValidator,
                trueVerifierFactory,
                rosterHistory,
                intakeEventCounter,
                null);

        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        assertNull(processor.processHashedEvent(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Duplicate events are discarded")
    void duplicateEventDiscarded() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        // First time — passes
        assertNotNull(processorWithTrueVerifier.processHashedEvent(event));
        assertEquals(0, exitedIntakePipelineCount.get());

        // Second time — duplicate
        assertNull(processorWithTrueVerifier.processHashedEvent(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Same descriptor with different signature is not a duplicate")
    void disparateSignatureNotDuplicate() {
        final PlatformEvent event1 = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        assertNotNull(processorWithTrueVerifier.processHashedEvent(event1));

        // Build a different event with the same creator/round (different random content → different signature)
        final PlatformEvent event2 = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        assertNotNull(processorWithTrueVerifier.processHashedEvent(event2));
        assertEquals(0, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Event failing signature verification is discarded")
    void signatureVerificationFailure() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        assertNull(processorWithFalseVerifier.processHashedEvent(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("RUNTIME events skip signature verification")
    void runtimeEventsSkipSigVerification() {
        final PlatformEvent runtimeEvent = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .setOrigin(EventOrigin.RUNTIME)
                .build();

        // Even with the false verifier, RUNTIME events should pass
        assertNotNull(processorWithFalseVerifier.processHashedEvent(runtimeEvent));
        assertEquals(0, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("GOSSIP events are subject to signature verification")
    void gossipEventsVerified() {
        final PlatformEvent gossipEvent = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .setOrigin(EventOrigin.GOSSIP)
                .build();

        // With false verifier, gossip event should be discarded
        assertNull(processorWithFalseVerifier.processHashedEvent(gossipEvent));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Roster not found for event's birth round")
    void rosterNotFound() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(PREVIOUS_ROSTER_NODE_ID)
                .setBirthRound(PREVIOUS_ROSTER_ROUND - 1)
                .build();

        assertNull(processorWithTrueVerifier.processHashedEvent(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Node missing from applicable roster")
    void nodeMissingFromRoster() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(99))
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        assertNull(processorWithTrueVerifier.processHashedEvent(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Previous roster is used for events from earlier rounds")
    void previousRosterUsed() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(PREVIOUS_ROSTER_NODE_ID)
                .setBirthRound(PREVIOUS_ROSTER_ROUND)
                .build();

        assertNotNull(processorWithTrueVerifier.processHashedEvent(event));
        assertEquals(0, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Roster update affects events with new birth rounds")
    void rosterUpdateAffectsNewBirthRounds() {
        // First event passes with the initial roster
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        assertNotNull(processorWithTrueVerifier.processHashedEvent(event));

        // Update to a roster that doesn't contain the node, effective at a new round
        final long newRosterRound = ACTIVE_ROSTER_ROUND + 10;
        final RosterWrapper emptyRoster = createRosterWrapper(List.of());
        final RosterWrapperHistory newHistory = createRosterWrapperHistory(newRosterRound, emptyRoster);
        processorWithTrueVerifier.updateRosterHistory(newHistory);

        // Event at the NEW birth round should fail — node not in the new roster
        final PlatformEvent event2 = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(newRosterRound)
                .build();

        assertNull(processorWithTrueVerifier.processHashedEvent(event2));
    }

    @Test
    @DisplayName("Clear resets deduplication state")
    void clearResetsDedup() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        // First pass
        assertNotNull(processorWithTrueVerifier.processHashedEvent(event));

        // Duplicate
        assertNull(processorWithTrueVerifier.processHashedEvent(event));
        assertEquals(1, exitedIntakePipelineCount.get());

        // Clear and retry — should pass again
        processorWithTrueVerifier.clear();
        assertNotNull(processorWithTrueVerifier.processHashedEvent(event));
    }
}
