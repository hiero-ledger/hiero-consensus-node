// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl.signature;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
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
import java.security.cert.CertificateEncodingException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.hiero.base.crypto.SignatureVerifier;
import org.hiero.consensus.event.IntakeEventCounter;
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

class EventSignatureValidatorTests {
    public static final int PREVIOUS_ROSTER_ROUND = 2;
    public static final int ACTIVE_ROSTER_ROUND = 3;
    public static final NodeId PREVIOUS_ROSTER_NODE_ID = NodeId.of(66);
    public static final NodeId ACTIVE_ROSTER_NODE_ID = NodeId.of(77);
    private Randotron random;
    private Metrics metrics;
    private FakeTime time;
    private AtomicLong exitedIntakePipelineCount;
    private IntakeEventCounter intakeEventCounter;

    /**
     * A verifier that always returns true.
     */
    private final SignatureVerifier trueVerifier = (data, signature, publicKey) -> true;

    /**
     * A verifier that always returns false.
     */
    private final SignatureVerifier falseVerifier = (data, signature, publicKey) -> false;

    private EventSignatureValidator validatorWithTrueVerifier;
    private EventSignatureValidator validatorWithFalseVerifier;

    private RosterWrapperHistory rosterHistory;

    /**
     * Generate a mock RosterEntry, with enough elements mocked to support the signature validation.
     *
     * @param nodeId the node id to use for the address
     * @return a mock roster entry
     */
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

        // create a rosterHistory with a previous roster and a active roster
        rosterHistory = buildRosterHistory(
                PREVIOUS_ROSTER_ROUND, ACTIVE_ROSTER_ROUND, EventSignatureValidatorTests::generateMockRosterEntry);

        validatorWithTrueVerifier =
                new DefaultEventSignatureValidator(metrics, time, trueVerifier, rosterHistory, intakeEventCounter);

        validatorWithFalseVerifier =
                new DefaultEventSignatureValidator(metrics, time, falseVerifier, rosterHistory, intakeEventCounter);
    }

    public RosterWrapperHistory buildRosterHistory(
            final long previousRound, final long round, final Function<NodeId, RosterEntry> rosterEntryGenerator) {
        final RosterEntry previousNodeRosterEntry = rosterEntryGenerator.apply(PREVIOUS_ROSTER_NODE_ID);
        final RosterEntry activeNodeRosterEntry = rosterEntryGenerator.apply(ACTIVE_ROSTER_NODE_ID);

        final RosterWrapper previousRoster = createRosterWrapper(previousNodeRosterEntry);
        final RosterWrapper activeRoster = createRosterWrapper(activeNodeRosterEntry);

        return createRosterWrapperHistory(round, activeRoster, previousRound, previousRoster);
    }

    @Test
    @DisplayName("An event with a lower round than the available in roster history should not validate")
    void rosterNotFoundForRound() {
        final EventSignatureValidator signatureValidator =
                new DefaultEventSignatureValidator(metrics, time, trueVerifier, rosterHistory, intakeEventCounter);

        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(PREVIOUS_ROSTER_NODE_ID)
                .setBirthRound(PREVIOUS_ROSTER_ROUND - 1)
                .build();

        assertNull(signatureValidator.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Node is missing from the applicable roster")
    void applicableRosterMissingNode() {
        // this creator isn't in the active roster, so verification will fail
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(99))
                .setBirthRound(PREVIOUS_ROSTER_ROUND)
                .build();

        assertNull(validatorWithTrueVerifier.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Node has a null public key")
    void missingPublicKey() {

        final Function<NodeId, RosterEntry> generateMockRosterEntry =
                id -> new RosterEntry(id.id(), 10, null, List.of());
        final RosterWrapperHistory rh =
                buildRosterHistory(PREVIOUS_ROSTER_ROUND, ACTIVE_ROSTER_ROUND, generateMockRosterEntry);

        final EventSignatureValidator validator =
                new DefaultEventSignatureValidator(metrics, time, trueVerifier, rh, intakeEventCounter);

        final NodeId nodeId = NodeId.of(88);

        final PlatformEvent event =
                new TestingEventBuilder(random).setCreatorId(nodeId).build();

        assertNull(validator.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Event passes validation if the signature verifies")
    void validSignature() {
        // create an event that should be validated with the activeRoster
        final PlatformEvent event1Valid = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        assertNotNull(validatorWithTrueVerifier.validateSignature(event1Valid));
        assertEquals(0, exitedIntakePipelineCount.get());

        // event2 is from a previous version, so the previous roster will be selected
        final PlatformEvent event2 = new TestingEventBuilder(random)
                .setCreatorId(PREVIOUS_ROSTER_NODE_ID)
                .setBirthRound(PREVIOUS_ROSTER_ROUND)
                .build();

        assertNotNull(validatorWithTrueVerifier.validateSignature(event2));
        assertEquals(0, exitedIntakePipelineCount.get());

        // similarly we test invalid events for each of the rosters and make sure they exited the pipeline
        final PlatformEvent event1Invalid = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(ACTIVE_ROSTER_NODE_ID.id() + 1))
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();
        final PlatformEvent event2Invalid = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(PREVIOUS_ROSTER_NODE_ID.id() + 1))
                .setBirthRound(PREVIOUS_ROSTER_ROUND)
                .build();

        assertNull(validatorWithTrueVerifier.validateSignature(event1Invalid));
        assertNull(validatorWithTrueVerifier.validateSignature(event2Invalid));
        assertEquals(2, exitedIntakePipelineCount.get());

        // make sure that events from any round number higher than ACTIVE_ROSTER_ROUND get validated by the
        // activeRoster
        final Random random = getRandomPrintSeed();
        random.ints(ACTIVE_ROSTER_ROUND, Integer.MAX_VALUE)
                .limit(10)
                .boxed()
                .map(r -> new TestingEventBuilder(this.random)
                        .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                        .setBirthRound(r)
                        .build())
                .forEach(e -> assertNotNull(validatorWithTrueVerifier.validateSignature(e)));

        // make sure that events from any round number higher than PREVIOUS_ROSTER_ROUND and lower than
        // ACTIVE_ROSTER_ROUND
        // get validated by the previous roster
        random.ints(PREVIOUS_ROSTER_ROUND, ACTIVE_ROSTER_ROUND)
                .limit(10)
                .boxed()
                .map(r -> new TestingEventBuilder(this.random)
                        .setCreatorId(PREVIOUS_ROSTER_NODE_ID)
                        .setBirthRound(r)
                        .build())
                .forEach(e -> assertNotNull(validatorWithTrueVerifier.validateSignature(e)));
    }

    @Test
    @DisplayName("Event fails validation if the signature does not verify")
    void verificationFails() {
        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        assertNotNull(validatorWithTrueVerifier.validateSignature(event));
        assertEquals(0, exitedIntakePipelineCount.get());

        assertNull(validatorWithFalseVerifier.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Ancient events are discarded")
    void ancientEvent() {
        final EventSignatureValidator validator =
                new DefaultEventSignatureValidator(metrics, time, trueVerifier, rosterHistory, intakeEventCounter);

        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .build();

        assertNotNull(validator.validateSignature(event));
        assertEquals(0, exitedIntakePipelineCount.get());

        validatorWithTrueVerifier.setEventWindow(
                EventWindowBuilder.builder().setAncientThreshold(100).build());

        assertNull(validatorWithTrueVerifier.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Events created by this runtime should not be validated")
    void runtimeCreatedEvent() {
        final PlatformEvent gossip = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .setOrigin(EventOrigin.GOSSIP)
                .build();
        assertNull(
                validatorWithFalseVerifier.validateSignature(gossip),
                "Gossip events should be validated, and in this case discarded");
        assertEquals(1, exitedIntakePipelineCount.get());

        final PlatformEvent runtime = new TestingEventBuilder(random)
                .setCreatorId(ACTIVE_ROSTER_NODE_ID)
                .setBirthRound(ACTIVE_ROSTER_ROUND)
                .setOrigin(EventOrigin.RUNTIME)
                .build();
        assertNotNull(validatorWithFalseVerifier.validateSignature(runtime), "Runtime events should be trusted");
        assertEquals(1, exitedIntakePipelineCount.get());
    }
}
