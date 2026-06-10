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
import com.hedera.hapi.platform.event.GossipEvent;
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
import org.hiero.consensus.event.validation.EventFieldValidator;
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
                PREVIOUS_ROSTER_ROUND, CURRENT_ROSTER_ROUND, EventIntakeProcessorTests::generateMockRosterEntry);

        processorWithTrueVerifier = new ConcurrentEventIntakeProcessor(
                metrics,
                time,
                eventHasher,
                passingValidator,
                trueVerifierFactory,
                rosterHistory,
                intakeEventCounter,
                null,
                false);

        processorWithFalseVerifier = new ConcurrentEventIntakeProcessor(
                metrics,
                time,
                eventHasher,
                passingValidator,
                falseVerifierFactory,
                rosterHistory,
                intakeEventCounter,
                null,
                false);
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
        final EventIntakeProcessor processor = new ConcurrentEventIntakeProcessor(
                metrics,
                time,
                eventHasher,
                failingValidator,
                trueVerifierFactory,
                rosterHistory,
                intakeEventCounter,
                null,
                false);

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
    @DisplayName("Roster update affects events with new birth rounds")
    void rosterUpdateAffectsNewBirthRounds() {
        // First event passes with the initial roster
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();

        assertNotNull(processorWithTrueVerifier.processHashedEvent(event));

        // Update to a roster that doesn't contain the node, effective at a new round
        final long newRosterRound = CURRENT_ROSTER_ROUND + 10;
        final Roster emptyRoster = new Roster(List.of());
        final Bytes hash = RosterUtils.hash(emptyRoster).getBytes();
        final RosterHistory newHistory =
                new RosterHistory(List.of(new RoundRosterPair(newRosterRound, hash)), Map.of(hash, emptyRoster));
        processorWithTrueVerifier.updateRosterHistory(newHistory);

        // Event at the NEW birth round should fail — node not in the new roster
        final PlatformEvent event2 = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(newRosterRound)
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

    // -------------------------------------------------------------------------
    // allowUnsignedPcesEvents tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Unsigned STORAGE event is accepted when allowUnsignedPcesEvents is true")
    void unsignedStorageEventAcceptedWhenFlagEnabled() {
        // Use the false verifier — any real signature check would fail.
        final ConcurrentEventIntakeProcessor processor = processorWithUnsignedFlag(falseVerifierFactory, true);

        final PlatformEvent unsignedStorageEvent =
                buildUnsignedStorageEvent(CURRENT_ROSTER_NODE_ID, CURRENT_ROSTER_ROUND);

        assertNotNull(
                processor.processHashedEvent(unsignedStorageEvent),
                "Unsigned STORAGE event should pass when allowUnsignedPcesEvents is true");
        assertEquals(0, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Signed STORAGE event is still validated when allowUnsignedPcesEvents is true")
    void signedStorageEventStillValidatedWhenFlagEnabled() {
        // The bypass applies only to empty-signature events.
        // A STORAGE event with a non-empty (but failing) signature must still be rejected.
        final ConcurrentEventIntakeProcessor processor = processorWithUnsignedFlag(falseVerifierFactory, true);

        // TestingEventBuilder produces a non-empty signature by default.
        final PlatformEvent signedStorageEvent = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .setOrigin(EventOrigin.STORAGE)
                .build();

        assertNull(
                processor.processHashedEvent(signedStorageEvent),
                "Signed STORAGE event with an invalid signature should still be rejected");
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Unsigned STORAGE event is rejected when allowUnsignedPcesEvents is false")
    void unsignedStorageEventRejectedWhenFlagDisabled() {
        // Default behaviour: unsigned STORAGE events are dropped.
        final ConcurrentEventIntakeProcessor processor = processorWithUnsignedFlag(falseVerifierFactory, false);

        final PlatformEvent unsignedStorageEvent =
                buildUnsignedStorageEvent(CURRENT_ROSTER_NODE_ID, CURRENT_ROSTER_ROUND);

        assertNull(
                processor.processHashedEvent(unsignedStorageEvent),
                "Unsigned STORAGE event should be rejected when allowUnsignedPcesEvents is false");
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Unsigned GOSSIP event is rejected even when allowUnsignedPcesEvents is true")
    void unsignedGossipEventAlwaysRejected() {
        // The bypass is STORAGE-origin only. Unsigned GOSSIP events must always fail,
        // regardless of the flag, to prevent spoofed gossip from bypassing validation.
        final ConcurrentEventIntakeProcessor processor = processorWithUnsignedFlag(falseVerifierFactory, true);

        final PlatformEvent template = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();
        final GossipEvent unsignedGossipEventProto = GossipEvent.newBuilder()
                .eventCore(template.getGossipEvent().eventCore())
                .signature(Bytes.EMPTY)
                .parents(template.getGossipEvent().parents())
                .transactions(template.getGossipEvent().transactions())
                .build();
        final PlatformEvent unsignedGossipEvent = new PlatformEvent(unsignedGossipEventProto, EventOrigin.GOSSIP);
        unsignedGossipEvent.setHash(template.getHash());

        assertNull(
                processor.processHashedEvent(unsignedGossipEvent),
                "Unsigned GOSSIP event must be rejected regardless of the allowUnsignedPcesEvents flag");
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Ancient unsigned STORAGE event is dropped before the bypass is checked")
    void ancientUnsignedStorageEventDropped() {
        // The ancient check fires before the signature bypass.
        final ConcurrentEventIntakeProcessor processor = processorWithUnsignedFlag(falseVerifierFactory, true);

        final PlatformEvent unsignedStorageEvent =
                buildUnsignedStorageEvent(CURRENT_ROSTER_NODE_ID, CURRENT_ROSTER_ROUND);

        processor.setEventWindow(
                EventWindowBuilder.builder().setAncientThreshold(100).build());

        assertNull(
                processor.processHashedEvent(unsignedStorageEvent),
                "Ancient events must be dropped before the unsigned bypass is consulted");
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Unsigned RUNTIME event passes regardless of allowUnsignedPcesEvents")
    void unsignedRuntimeEventPassesRegardlessOfFlag() {
        // RUNTIME events are trusted unconditionally — the bypass does not interact with that path.
        final PlatformEvent template = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();
        final GossipEvent unsignedGossipEventProto = GossipEvent.newBuilder()
                .eventCore(template.getGossipEvent().eventCore())
                .signature(Bytes.EMPTY)
                .parents(template.getGossipEvent().parents())
                .transactions(template.getGossipEvent().transactions())
                .build();
        final PlatformEvent unsignedRuntimeEvent = new PlatformEvent(unsignedGossipEventProto, EventOrigin.RUNTIME);
        unsignedRuntimeEvent.setHash(template.getHash());

        final ConcurrentEventIntakeProcessor processorFlagOff = processorWithUnsignedFlag(falseVerifierFactory, false);
        final ConcurrentEventIntakeProcessor processorFlagOn = processorWithUnsignedFlag(falseVerifierFactory, true);

        assertNotNull(
                processorFlagOff.processHashedEvent(unsignedRuntimeEvent),
                "Unsigned RUNTIME event should pass regardless (flag off)");
        assertNotNull(
                processorFlagOn.processHashedEvent(unsignedRuntimeEvent),
                "Unsigned RUNTIME event should pass regardless (flag on)");
        assertEquals(0, exitedIntakePipelineCount.get());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a processor with the allowUnsignedPcesEvents flag configured.
     * All other parameters mirror the standard @BeforeEach setup.
     */
    private ConcurrentEventIntakeProcessor processorWithUnsignedFlag(
            final Function<PublicKey, BytesSignatureVerifier> verifierFactory, final boolean allowUnsignedPcesEvents) {
        return new ConcurrentEventIntakeProcessor(
                metrics,
                time,
                eventHasher,
                passingValidator,
                verifierFactory,
                rosterHistory,
                intakeEventCounter,
                null,
                allowUnsignedPcesEvents);
    }

    /**
     * Constructs a STORAGE-origin event with an empty (unsigned) signature.
     *
     * <p>GossipEvent is an immutable protobuf record — PlatformEvent has no setSignature.
     * We follow the same approach used by BlockStreamEventBuilder: rebuild the GossipEvent
     * with Bytes.EMPTY as the signature while keeping the original EventCore, parents,
     * and transactions intact.
     */
    private PlatformEvent buildUnsignedStorageEvent(final NodeId creatorId, final long birthRound) {
        final PlatformEvent template = new TestingEventBuilder(random)
                .setCreatorId(creatorId)
                .setBirthRound(birthRound)
                .build();
        final GossipEvent unsignedGossipEvent = GossipEvent.newBuilder()
                .eventCore(template.getGossipEvent().eventCore())
                .signature(Bytes.EMPTY)
                .parents(template.getGossipEvent().parents())
                .transactions(template.getGossipEvent().transactions())
                .build();
        final PlatformEvent unsignedEvent = new PlatformEvent(unsignedGossipEvent, EventOrigin.STORAGE);
        unsignedEvent.setHash(template.getHash());
        return unsignedEvent;
    }
}
