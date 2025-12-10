// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.state.history.WrapsPhase;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.history.impl.WrapsMpcStateMachine.Transition;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WrapsMpcStateMachineTest {
    private static final long NODE_1 = 1L;
    private static final long NODE_2 = 2L;
    private static final long NODE_3 = 3L;
    private static final Bytes MESSAGE = Bytes.wrap("test-message");
    private static final Duration GRACE_PERIOD = Duration.ofSeconds(10);
    private static final Instant BASE_TIME = Instant.EPOCH;

    private WrapsMpcStateMachine subject;
    private SortedMap<Long, Long> sourceWeights;
    private SortedMap<Long, Long> targetWeights;
    private RosterTransitionWeights weights;
    private Map<WrapsPhase, SortedMap<Long, WrapsMessagePublication>> phaseMessages;

    @BeforeEach
    void setUp() {
        subject = new WrapsMpcStateMachine();
        sourceWeights = new TreeMap<>();
        targetWeights = new TreeMap<>();
        phaseMessages = new HashMap<>();
    }

    @Nested
    class TransitionRecordTests {
        @Test
        void rejectedAtCreatesCorrectTransition() {
            final var transition = Transition.rejectedAt(R1);

            assertFalse(transition.publicationAccepted());
            assertEquals(R1, transition.newCurrentPhase());
            assertNull(transition.gracePeriodEndTimeUpdate());
        }

        @Test
        void incorporatedInCreatesCorrectTransition() {
            final var transition = Transition.incorporatedIn(R2);

            assertTrue(transition.publicationAccepted());
            assertEquals(R2, transition.newCurrentPhase());
            assertNull(transition.gracePeriodEndTimeUpdate());
        }

        @Test
        void advanceToCreatesCorrectTransition() {
            final var endTime = Instant.now();
            final var transition = Transition.advanceTo(R3, endTime);

            assertTrue(transition.publicationAccepted());
            assertEquals(R3, transition.newCurrentPhase());
            assertEquals(endTime, transition.gracePeriodEndTimeUpdate());
        }

        @Test
        void transitionRequiresNonNullPhase() {
            assertThrows(NullPointerException.class, () -> new Transition(true, null, null));
        }
    }

    @Nested
    class OnNextAggregatePhaseTests {
        @Test
        void rejectsPublicationWhenCurrentPhaseIsAggregate() {
            setupTwoNodeWeights();
            final var publication = createPublication(NODE_1, AGGREGATE, BASE_TIME);

            final var transition = subject.onNext(publication, AGGREGATE, weights, GRACE_PERIOD, phaseMessages);

            assertFalse(transition.publicationAccepted());
            assertEquals(AGGREGATE, transition.newCurrentPhase());
            assertNull(transition.gracePeriodEndTimeUpdate());
        }
    }

    @Nested
    class OnNextPhaseMismatchTests {
        @Test
        void rejectsR2PublicationWhenCurrentPhaseIsR1() {
            setupTwoNodeWeights();
            final var publication = createPublication(NODE_1, R2, BASE_TIME);

            final var transition = subject.onNext(publication, R1, weights, GRACE_PERIOD, phaseMessages);

            assertFalse(transition.publicationAccepted());
            assertEquals(R1, transition.newCurrentPhase());
        }

        @Test
        void rejectsR1PublicationWhenCurrentPhaseIsR2() {
            setupTwoNodeWeights();
            final var publication = createPublication(NODE_1, R1, BASE_TIME);

            final var transition = subject.onNext(publication, R2, weights, GRACE_PERIOD, phaseMessages);

            assertFalse(transition.publicationAccepted());
            assertEquals(R2, transition.newCurrentPhase());
        }

        @Test
        void rejectsR3PublicationWhenCurrentPhaseIsR2() {
            setupTwoNodeWeights();
            final var publication = createPublication(NODE_1, R3, BASE_TIME);

            final var transition = subject.onNext(publication, R2, weights, GRACE_PERIOD, phaseMessages);

            assertFalse(transition.publicationAccepted());
            assertEquals(R2, transition.newCurrentPhase());
        }
    }

    @Nested
    class OnNextR1PhaseTests {
        @Test
        void acceptsFirstR1PublicationAndIncorporates() {
            setupTwoNodeWeights();
            final var publication = createPublication(NODE_1, R1, BASE_TIME);

            final var transition = subject.onNext(publication, R1, weights, GRACE_PERIOD, phaseMessages);

            assertTrue(transition.publicationAccepted());
            assertEquals(R1, transition.newCurrentPhase());
            assertTrue(phaseMessages.get(R1).containsKey(NODE_1));
        }

        @Test
        void rejectsDuplicateR1PublicationFromSameNode() {
            setupTwoNodeWeights();
            final var first = createPublication(NODE_1, R1, BASE_TIME);
            final var duplicate = createPublication(NODE_1, R1, BASE_TIME.plusSeconds(1));

            subject.onNext(first, R1, weights, GRACE_PERIOD, phaseMessages);
            final var transition = subject.onNext(duplicate, R1, weights, GRACE_PERIOD, phaseMessages);

            assertFalse(transition.publicationAccepted());
            assertEquals(R1, transition.newCurrentPhase());
        }

        @Test
        void advancesToR2WhenMoreThanHalfWeightReached() {
            setupTwoNodeWeights();
            final var first = createPublication(NODE_1, R1, BASE_TIME);
            final var second = createPublication(NODE_2, R1, BASE_TIME.plusSeconds(1));

            subject.onNext(first, R1, weights, GRACE_PERIOD, phaseMessages);
            final var transition = subject.onNext(second, R1, weights, GRACE_PERIOD, phaseMessages);

            assertTrue(transition.publicationAccepted());
            assertEquals(R2, transition.newCurrentPhase());
            assertEquals(BASE_TIME.plusSeconds(1).plus(GRACE_PERIOD), transition.gracePeriodEndTimeUpdate());
        }

        @Test
        void staysInR1WhenNotEnoughWeight() {
            setupThreeNodeWeightsWithMajorityRequired();
            final var first = createPublication(NODE_1, R1, BASE_TIME);

            final var transition = subject.onNext(first, R1, weights, GRACE_PERIOD, phaseMessages);

            assertTrue(transition.publicationAccepted());
            assertEquals(R1, transition.newCurrentPhase());
        }

        @Test
        void advancesToR2WhenMajorityWeightReachedWithThreeNodes() {
            setupThreeNodeWeightsWithMajorityRequired();
            final var first = createPublication(NODE_1, R1, BASE_TIME);
            final var second = createPublication(NODE_2, R1, BASE_TIME.plusSeconds(1));

            subject.onNext(first, R1, weights, GRACE_PERIOD, phaseMessages);
            final var transition = subject.onNext(second, R1, weights, GRACE_PERIOD, phaseMessages);

            assertTrue(transition.publicationAccepted());
            assertEquals(R2, transition.newCurrentPhase());
        }
    }

    @Nested
    class OnNextR2PhaseTests {
        @BeforeEach
        void setupR1Participants() {
            setupTwoNodeWeights();
            phaseMessages.put(R1, new TreeMap<>());
            phaseMessages.get(R1).put(NODE_1, createPublication(NODE_1, R1, BASE_TIME));
            phaseMessages.get(R1).put(NODE_2, createPublication(NODE_2, R1, BASE_TIME));
        }

        @Test
        void rejectsR2PublicationFromNodeNotInR1() {
            final var publication = createPublication(NODE_3, R2, BASE_TIME);

            final var transition = subject.onNext(publication, R2, weights, GRACE_PERIOD, phaseMessages);

            assertFalse(transition.publicationAccepted());
            assertEquals(R2, transition.newCurrentPhase());
        }

        @Test
        void acceptsR2PublicationFromR1Participant() {
            final var publication = createPublication(NODE_1, R2, BASE_TIME);

            final var transition = subject.onNext(publication, R2, weights, GRACE_PERIOD, phaseMessages);

            assertTrue(transition.publicationAccepted());
            assertEquals(R2, transition.newCurrentPhase());
        }

        @Test
        void rejectsDuplicateR2PublicationFromSameNode() {
            final var first = createPublication(NODE_1, R2, BASE_TIME);
            final var duplicate = createPublication(NODE_1, R2, BASE_TIME.plusSeconds(1));

            subject.onNext(first, R2, weights, GRACE_PERIOD, phaseMessages);
            final var transition = subject.onNext(duplicate, R2, weights, GRACE_PERIOD, phaseMessages);

            assertFalse(transition.publicationAccepted());
            assertEquals(R2, transition.newCurrentPhase());
        }

        @Test
        void advancesToR3WhenAllR1ParticipantsSubmitR2() {
            final var first = createPublication(NODE_1, R2, BASE_TIME);
            final var second = createPublication(NODE_2, R2, BASE_TIME.plusSeconds(1));

            subject.onNext(first, R2, weights, GRACE_PERIOD, phaseMessages);
            final var transition = subject.onNext(second, R2, weights, GRACE_PERIOD, phaseMessages);

            assertTrue(transition.publicationAccepted());
            assertEquals(R3, transition.newCurrentPhase());
            assertEquals(BASE_TIME.plusSeconds(1).plus(GRACE_PERIOD), transition.gracePeriodEndTimeUpdate());
        }
    }

    @Nested
    class OnNextR3PhaseTests {
        @BeforeEach
        void setupR1AndR2Participants() {
            setupTwoNodeWeights();
            phaseMessages.put(R1, new TreeMap<>());
            phaseMessages.get(R1).put(NODE_1, createPublication(NODE_1, R1, BASE_TIME));
            phaseMessages.get(R1).put(NODE_2, createPublication(NODE_2, R1, BASE_TIME));
            phaseMessages.put(R2, new TreeMap<>());
            phaseMessages.get(R2).put(NODE_1, createPublication(NODE_1, R2, BASE_TIME));
            phaseMessages.get(R2).put(NODE_2, createPublication(NODE_2, R2, BASE_TIME));
        }

        @Test
        void rejectsR3PublicationFromNodeNotInR1() {
            final var publication = createPublication(NODE_3, R3, BASE_TIME);

            final var transition = subject.onNext(publication, R3, weights, GRACE_PERIOD, phaseMessages);

            assertFalse(transition.publicationAccepted());
            assertEquals(R3, transition.newCurrentPhase());
        }

        @Test
        void acceptsR3PublicationFromR1Participant() {
            final var publication = createPublication(NODE_1, R3, BASE_TIME);

            final var transition = subject.onNext(publication, R3, weights, GRACE_PERIOD, phaseMessages);

            assertTrue(transition.publicationAccepted());
            assertEquals(R3, transition.newCurrentPhase());
        }

        @Test
        void rejectsDuplicateR3PublicationFromSameNode() {
            final var first = createPublication(NODE_1, R3, BASE_TIME);
            final var duplicate = createPublication(NODE_1, R3, BASE_TIME.plusSeconds(1));

            subject.onNext(first, R3, weights, GRACE_PERIOD, phaseMessages);
            final var transition = subject.onNext(duplicate, R3, weights, GRACE_PERIOD, phaseMessages);

            assertFalse(transition.publicationAccepted());
            assertEquals(R3, transition.newCurrentPhase());
        }

        @Test
        void advancesToAggregateWhenAllR1ParticipantsSubmitR3() {
            final var first = createPublication(NODE_1, R3, BASE_TIME);
            final var second = createPublication(NODE_2, R3, BASE_TIME.plusSeconds(1));

            subject.onNext(first, R3, weights, GRACE_PERIOD, phaseMessages);
            final var transition = subject.onNext(second, R3, weights, GRACE_PERIOD, phaseMessages);

            assertTrue(transition.publicationAccepted());
            assertEquals(AGGREGATE, transition.newCurrentPhase());
            assertNull(transition.gracePeriodEndTimeUpdate());
        }
    }

    @Nested
    class NullParameterTests {
        @Test
        void throwsOnNullPublication() {
            setupTwoNodeWeights();
            assertThrows(
                    NullPointerException.class, () -> subject.onNext(null, R1, weights, GRACE_PERIOD, phaseMessages));
        }

        @Test
        void throwsOnNullCurrentPhase() {
            setupTwoNodeWeights();
            final var publication = createPublication(NODE_1, R1, BASE_TIME);
            assertThrows(
                    NullPointerException.class,
                    () -> subject.onNext(publication, null, weights, GRACE_PERIOD, phaseMessages));
        }

        @Test
        void throwsOnNullWeights() {
            final var publication = createPublication(NODE_1, R1, BASE_TIME);
            assertThrows(
                    NullPointerException.class,
                    () -> subject.onNext(publication, R1, null, GRACE_PERIOD, phaseMessages));
        }

        @Test
        void throwsOnNullGracePeriod() {
            setupTwoNodeWeights();
            final var publication = createPublication(NODE_1, R1, BASE_TIME);
            assertThrows(
                    NullPointerException.class, () -> subject.onNext(publication, R1, weights, null, phaseMessages));
        }

        @Test
        void throwsOnNullPhaseMessages() {
            setupTwoNodeWeights();
            final var publication = createPublication(NODE_1, R1, BASE_TIME);
            assertThrows(
                    NullPointerException.class, () -> subject.onNext(publication, R1, weights, GRACE_PERIOD, null));
        }
    }

    private void setupTwoNodeWeights() {
        sourceWeights.put(NODE_1, 1L);
        sourceWeights.put(NODE_2, 1L);
        targetWeights.put(NODE_1, 1L);
        targetWeights.put(NODE_2, 1L);
        weights = new RosterTransitionWeights(sourceWeights, targetWeights);
    }

    private void setupThreeNodeWeightsWithMajorityRequired() {
        sourceWeights.put(NODE_1, 1L);
        sourceWeights.put(NODE_2, 1L);
        sourceWeights.put(NODE_3, 1L);
        targetWeights.put(NODE_1, 1L);
        targetWeights.put(NODE_2, 1L);
        targetWeights.put(NODE_3, 1L);
        weights = new RosterTransitionWeights(sourceWeights, targetWeights);
    }

    private WrapsMessagePublication createPublication(long nodeId, WrapsPhase phase, Instant receiptTime) {
        return new WrapsMessagePublication(nodeId, MESSAGE, phase, receiptTime);
    }
}
