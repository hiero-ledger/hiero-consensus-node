// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.validation;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
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
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.crypto.SignatureVerifier;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.test.fixtures.crypto.PreGeneratedX509Certs;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventSignatureValidatorTests {
    public static final int PREVIOUS_ROSTER_ROUND = 2;
    public static final int CURRENT_ROSTER_ROUND = 3;
    public static final NodeId PREVIOUS_ROSTER_NODE_ID = NodeId.of(66);
    public static final NodeId CURRENT_ROSTER_NODE_ID = NodeId.of(77);
    private Randotron random;
    private PlatformContext platformContext;
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

    private RosterHistory rosterHistory;

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
                    Bytes.wrap(PreGeneratedX509Certs.getSigCert(nodeId.id())
                            .getCertificate()
                            .getEncoded()),
                    List.of());
        } catch (CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setup() {
        random = Randotron.create();
        time = new FakeTime();
        platformContext = TestPlatformContextBuilder.create().withTime(time).build();

        exitedIntakePipelineCount = new AtomicLong(0);
        intakeEventCounter = mock(IntakeEventCounter.class);
        doAnswer(invocation -> {
                    exitedIntakePipelineCount.incrementAndGet();
                    return null;
                })
                .when(intakeEventCounter)
                .eventExitedIntakePipeline(any());

        // create a rosterHistory with a previous roster and a current roster
        rosterHistory = buildRosterHistory(
                PREVIOUS_ROSTER_ROUND, CURRENT_ROSTER_ROUND, EventSignatureValidatorTests::generateMockRosterEntry);

        validatorWithTrueVerifier =
                new DefaultEventSignatureValidator(platformContext, trueVerifier, rosterHistory, intakeEventCounter);

        validatorWithFalseVerifier =
                new DefaultEventSignatureValidator(platformContext, falseVerifier, rosterHistory, intakeEventCounter);
    }

    public RosterHistory buildRosterHistory(
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
    @DisplayName("An event with a lower round than the available in roster history should not validate")
    void rosterNotFoundForRound() {
        final EventSignatureValidator signatureValidator =
                new DefaultEventSignatureValidator(platformContext, trueVerifier, rosterHistory, intakeEventCounter);

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
        // this creator isn't in the current roster, so verification will fail
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
        RosterHistory rh = buildRosterHistory(PREVIOUS_ROSTER_ROUND, CURRENT_ROSTER_ROUND, generateMockRosterEntry);

        EventSignatureValidator validator =
                new DefaultEventSignatureValidator(platformContext, trueVerifier, rh, intakeEventCounter);

        final NodeId nodeId = NodeId.of(88);

        final PlatformEvent event =
                new TestingEventBuilder(random).setCreatorId(nodeId).build();

        assertNull(validator.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Event passes validation if the signature verifies")
    void validSignature() {
        // create an event that should be validated with the currentRoster
        final PlatformEvent event1Valid = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
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
                .setCreatorId(NodeId.of(CURRENT_ROSTER_NODE_ID.id() + 1))
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();
        final PlatformEvent event2Invalid = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(PREVIOUS_ROSTER_NODE_ID.id() + 1))
                .setBirthRound(PREVIOUS_ROSTER_ROUND)
                .build();

        assertNull(validatorWithTrueVerifier.validateSignature(event1Invalid));
        assertNull(validatorWithTrueVerifier.validateSignature(event2Invalid));
        assertEquals(2, exitedIntakePipelineCount.get());

        // make sure that events from any round number higher than CURRENT_ROSTER_ROUND get validated by the
        // currentRoster
        final Random random = getRandomPrintSeed();
        random.ints(CURRENT_ROSTER_ROUND, Integer.MAX_VALUE)
                .limit(10)
                .boxed()
                .map(r -> new TestingEventBuilder(this.random)
                        .setCreatorId(CURRENT_ROSTER_NODE_ID)
                        .setBirthRound(r)
                        .build())
                .forEach(e -> assertNotNull(validatorWithTrueVerifier.validateSignature(e)));

        // make sure that events from any round number higher than PREVIOUS_ROSTER_ROUND and lower than
        // CURRENT_ROSTER_ROUND
        // get validated by the previous roster
        random.ints(PREVIOUS_ROSTER_ROUND, CURRENT_ROSTER_ROUND)
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
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();

        assertNotNull(validatorWithTrueVerifier.validateSignature(event));
        assertEquals(0, exitedIntakePipelineCount.get());

        assertNull(validatorWithFalseVerifier.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }

    @Test
    @DisplayName("Ancient events are discarded")
    void ancientEvent() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final EventSignatureValidator validator =
                new DefaultEventSignatureValidator(platformContext, trueVerifier, rosterHistory, intakeEventCounter);

        final PlatformEvent event = new TestingEventBuilder(random)
                .setCreatorId(CURRENT_ROSTER_NODE_ID)
                .setBirthRound(CURRENT_ROSTER_ROUND)
                .build();

        assertNotNull(validator.validateSignature(event));
        assertEquals(0, exitedIntakePipelineCount.get());

        validatorWithTrueVerifier.setEventWindow(
                EventWindowBuilder.builder().setAncientThreshold(100).build());

        assertNull(validatorWithTrueVerifier.validateSignature(event));
        assertEquals(1, exitedIntakePipelineCount.get());
    }
}
