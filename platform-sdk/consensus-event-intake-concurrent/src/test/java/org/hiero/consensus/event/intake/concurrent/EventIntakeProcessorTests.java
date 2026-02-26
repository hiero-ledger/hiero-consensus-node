// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.metrics.api.Metrics;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.hiero.base.crypto.BytesSignatureVerifier;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.test.fixtures.crypto.PreGeneratedX509Certs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventIntakeProcessorTests {

    private static final int PREVIOUS_ROSTER_ROUND = 2;
    private static final int CURRENT_ROSTER_ROUND = 3;
    private static final NodeId PREVIOUS_ROSTER_NODE_ID = NodeId.of(66);
    private static final NodeId CURRENT_ROSTER_NODE_ID = NodeId.of(77);

    private Randotron random;
    private Metrics metrics;
    private FakeTime time;
    private AtomicLong exitedIntakePipelineCount;
    private IntakeEventCounter intakeEventCounter;
    private RosterHistory rosterHistory;

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
        } catch (CertificateEncodingException e) {
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
                PREVIOUS_ROSTER_ROUND,
                CURRENT_ROSTER_ROUND,
                EventIntakeProcessorTests::generateMockRosterEntry);

        processorWithTrueVerifier = new DefaultEventIntakeProcessor(
                metrics, time, eventHasher, passingValidator, trueVerifierFactory,
                rosterHistory, intakeEventCounter);

        processorWithFalseVerifier = new DefaultEventIntakeProcessor(
                metrics, time, eventHasher, passingValidator, falseVerifierFactory,
                rosterHistory, intakeEventCounter);
    }

    private RosterHistory buildRosterHistory(
            final long previousRound, final long round, Function<NodeId, RosterEntry> rosterEntryGenerator) {
        final List<RoundRosterPair> roundRosterPairList = new ArrayList<>();
        final Map<Bytes, Roster> rosterMap = new HashMap<>();

        final RosterEntry previousNodeRosterEntry = rosterEntryGenerator.apply(PREVIOUS_ROSTER_NODE_ID);
        final RosterEntry currentNodeRosterEntry = rosterEntryGenerator.apply(CURRENT_ROSTER_NODE_ID);

        final Roster previousRoster = new Roster(List.of(previousNodeRosterEntry));
        final Roster currentRoster = new Roster(List.of(currentNodeRosterEntry));

        final Bytes currentHash = RosterUtils.hash(currentRoster).getBytes();
        roundRosterPairList.add(new RoundRosterPair(round, currentHash));
        rosterMap.put(currentHash, currentRoster);

        final Bytes previousHash = RosterUtils.hash(previousRoster).getBytes();
        roundRosterPairList.add(new RoundRosterPair(previousRound, previousHash));
        rosterMap.put(previousHash, previousRoster);

        return new RosterHistory(roundRosterPairList, rosterMap);
    }

    @Test
    @DisplayName("Valid gossip event passes all stages")
    void validGossipEvent() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();

        assertNotNull(processorWithTrueVerifier.processHashedEvent(event));
        assertEquals(0, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Ancient events are discarded before any other work")
    void ancientEventDiscarded() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();

        processorWithTrueVerifier.setEventWindow(
                EventWindowBuilder.builder().setAncientThreshold(100).build());

        assertNull(processorWithTrueVerifier.processHashedEvent(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Event failing field validation is discarded")
    void validationFailure() {
        final EventIntakeProcessor processor = new DefaultEventIntakeProcessor(
                metrics, time, eventHasher, failingValidator, trueVerifierFactory,
                rosterHistory, intakeEventCounter);

        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();

        assertNull(processor.processHashedEvent(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Duplicate events are discarded")
    void duplicateEventDiscarded() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
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
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();

        assertNotNull(processorWithTrueVerifier.processHashedEvent(event1));

        // Build a different event with the same creator/round (different random content → different signature)
        final PlatformEvent event2 = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();

        assertNotNull(processorWithTrueVerifier.processHashedEvent(event2));
        assertEquals(0, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Event failing signature verification is discarded")
    void signatureVerificationFailure() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();

        assertNull(processorWithFalseVerifier.processHashedEvent(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("RUNTIME events skip signature verification")
    void runtimeEventsSkipSigVerification() {
        final PlatformEvent runtimeEvent = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
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
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
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
                .setBirthRound(CURRENT_ROSTER_ROUND)
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
    @DisplayName("Roster update clears verifier cache")
    void rosterUpdateClearsCache() {
        // First event passes with the initial roster
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();

        assertNotNull(processorWithTrueVerifier.processHashedEvent(event));

        // Update to a roster that doesn't contain the node
        final Roster emptyRoster = new Roster(List.of());
        final Bytes hash = RosterUtils.hash(emptyRoster).getBytes();
        final RosterHistory newHistory = new RosterHistory(
                List.of(new RoundRosterPair(CURRENT_ROSTER_ROUND, hash)),
                Map.of(hash, emptyRoster));
        processorWithTrueVerifier.updateRosterHistory(newHistory);

        // Build a new event (different from the first to avoid dedup)
        final PlatformEvent event2 = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();

        assertNull(processorWithTrueVerifier.processHashedEvent(event2));
    }

    @Test
    @DisplayName("Clear resets deduplication state")
    void clearResetsDedup() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
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
