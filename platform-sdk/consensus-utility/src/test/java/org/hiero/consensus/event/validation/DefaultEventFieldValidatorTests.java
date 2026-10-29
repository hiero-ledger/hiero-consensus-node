// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.transaction.TransactionLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * Tests for {@link DefaultEventFieldValidator}
 */
class DefaultEventFieldValidatorTests {

    private static final TransactionLimits TRANSACTION_LIMITS = new TransactionLimits(133120, 245760);

    /** The first birth round hashed with SHA-256. */
    private static final long CUTOVER = 100;

    private static final int SHA_256_LENGTH = 32;
    private static final int SHA_384_LENGTH = 48;
    private static final int SHA_512_LENGTH = 64;

    private Randotron random;
    private DefaultEventFieldValidator validator;

    @BeforeEach
    void setup() {
        random = Randotron.create();
        validator = new DefaultEventFieldValidator(new NoOpMetrics(), new FakeTime(), TRANSACTION_LIMITS);
    }

    @AfterEach
    void tearDown() {
        // restore the default so tests do not leak static state
        EventHashFactory.initialize(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("An event with null fields is invalid")
    void nullFields() {
        final PlatformEvent platformEvent = Mockito.mock(PlatformEvent.class);

        final GossipEvent wholeEvent = new TestingEventBuilder(random)
                .setSystemTransactionCount(1)
                .setAppTransactionCount(2)
                .setSelfParent(new TestingEventBuilder(random).build())
                .setOtherParent(new TestingEventBuilder(random).build())
                .build()
                .getGossipEvent();

        final GossipEvent noEventCore = GossipEvent.newBuilder()
                .eventCore((EventCore) null)
                .signature(wholeEvent.signature())
                .transactions(wholeEvent.transactions())
                .build();
        when(platformEvent.getGossipEvent()).thenReturn(noEventCore);
        assertThat(validator.isValid(platformEvent)).isFalse();

        final GossipEvent noTimeCreated = GossipEvent.newBuilder()
                .eventCore(EventCore.newBuilder().timeCreated((Timestamp) null).build())
                .signature(wholeEvent.signature())
                .transactions(wholeEvent.transactions())
                .build();
        when(platformEvent.getGossipEvent()).thenReturn(noTimeCreated);
        assertThat(validator.isValid(platformEvent)).isFalse();

        final GossipEvent nullTransaction = GossipEvent.newBuilder()
                .eventCore(wholeEvent.eventCore())
                .signature(wholeEvent.signature())
                .transactions(List.of(Bytes.EMPTY))
                .build();
        when(platformEvent.getGossipEvent()).thenReturn(nullTransaction);
        assertThat(validator.isValid(platformEvent)).isFalse();

        final ArrayList<EventDescriptor> parents = new ArrayList<>();
        parents.add(null);
        final GossipEvent nullParent = GossipEvent.newBuilder()
                .eventCore(wholeEvent.eventCore())
                .signature(wholeEvent.signature())
                .transactions(wholeEvent.transactions())
                .parents(parents)
                .build();
        when(platformEvent.getGossipEvent()).thenReturn(nullParent);
        assertThat(validator.isValid(platformEvent)).isFalse();
    }

    static Stream<Arguments> invalidParentHashLengths() {
        return Stream.of(
                // pre-cutover parents must be SHA-384
                Arguments.of(CUTOVER, CUTOVER - 1, SHA_256_LENGTH),
                Arguments.of(CUTOVER, CUTOVER - 1, SHA_384_LENGTH - 2),
                Arguments.of(CUTOVER, CUTOVER - 1, SHA_512_LENGTH),
                // post-cutover parents must be SHA-256
                Arguments.of(CUTOVER, CUTOVER, SHA_384_LENGTH),
                Arguments.of(CUTOVER, CUTOVER, SHA_256_LENGTH - 2),
                Arguments.of(CUTOVER, CUTOVER, SHA_512_LENGTH),
                Arguments.of(CUTOVER, CUTOVER + 1, SHA_384_LENGTH),
                // no cutover configured, every parent must be SHA-384
                Arguments.of(Long.MAX_VALUE, CUTOVER - 1, SHA_256_LENGTH),
                Arguments.of(Long.MAX_VALUE, 1_000_000L, SHA_256_LENGTH),
                // network started with SHA-256 at genesis, every parent must be SHA-256
                Arguments.of(0L, 1L, SHA_384_LENGTH),
                Arguments.of(0L, CUTOVER, SHA_384_LENGTH));
    }

    @ParameterizedTest(name = "cutover {0}, parent birth round {1}, hash length {2}")
    @MethodSource("invalidParentHashLengths")
    @DisplayName("An event with a parent hash length that does not match the parent birth round is invalid")
    void parentHashLengthMustMatchParentBirthRound(
            final long cutover, final long parentBirthRound, final int hashLength) {
        EventHashFactory.initialize(cutover);

        final PlatformEvent platformEvent = eventWithParents(List.of(descriptor(0, parentBirthRound, hashLength)));

        assertThat(validator.isValid(platformEvent)).isFalse();
    }

    static Stream<Arguments> invalidParentAmongValidParents() {
        return Stream.of(
                // invalid parent last
                Arguments.of(
                        List.of(CUTOVER - 1, CUTOVER, CUTOVER),
                        List.of(SHA_384_LENGTH, SHA_256_LENGTH, SHA_384_LENGTH)),
                // invalid parent first
                Arguments.of(
                        List.of(CUTOVER, CUTOVER - 1, CUTOVER),
                        List.of(SHA_384_LENGTH, SHA_384_LENGTH, SHA_256_LENGTH)),
                // invalid parent in the middle
                Arguments.of(
                        List.of(CUTOVER, CUTOVER - 1, CUTOVER - 1),
                        List.of(SHA_256_LENGTH, SHA_256_LENGTH, SHA_384_LENGTH)));
    }

    @ParameterizedTest(name = "parent birth rounds {0}, hash lengths {1}")
    @MethodSource("invalidParentAmongValidParents")
    @DisplayName("Every parent hash length is checked, not only the first")
    void everyParentHashLengthIsChecked(final List<Long> parentBirthRounds, final List<Integer> hashLengths) {
        EventHashFactory.initialize(CUTOVER);

        final List<EventDescriptor> parents = new ArrayList<>();
        for (int i = 0; i < parentBirthRounds.size(); i++) {
            parents.add(descriptor(i, parentBirthRounds.get(i), hashLengths.get(i)));
        }

        assertThat(validator.isValid(eventWithParents(parents))).isFalse();
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

        assertThat(validator.isValid(event)).isFalse();
    }

    @Test
    @DisplayName("An event with duplicate parents is invalid")
    void duplicateParents() {
        final PlatformEvent parent = new TestingEventBuilder(random).build();
        final PlatformEvent invalidEvent = new TestingEventBuilder(random)
                .setSelfParent(parent)
                .setOtherParent(parent)
                .build();

        assertThat(validator.isValid(invalidEvent)).isFalse();
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

        assertThat(validator.isValid(new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(0))
                        .setSelfParent(selfParent1)
                        .setOtherParent(otherParent1)
                        .setBirthRound(6)
                        .build()))
                .isFalse();
        assertThat(validator.isValid(new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(0))
                        .setSelfParent(selfParent2)
                        .setOtherParent(otherParent2)
                        .setBirthRound(6)
                        .build()))
                .isFalse();
        assertThat(validator.isValid(new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(0))
                        .setSelfParent(selfParent1)
                        .setOtherParent(otherParent1)
                        .setBirthRound(4)
                        .build()))
                .isFalse();
        assertThat(validator.isValid(new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(0))
                        .setSelfParent(selfParent2)
                        .setOtherParent(otherParent2)
                        .setBirthRound(4)
                        .build()))
                .isFalse();
        assertThat(validator.isValid(new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(0))
                        .setSelfParent(selfParent1)
                        .setOtherParent(otherParent1)
                        .setBirthRound(7)
                        .build()))
                .isTrue();
        assertThat(validator.isValid(new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(0))
                        .setSelfParent(selfParent2)
                        .setOtherParent(otherParent2)
                        .setBirthRound(7)
                        .build()))
                .isTrue();
    }

    static Stream<Arguments> validParentBirthRounds() {
        return Stream.of(
                // both parents of the same type
                Arguments.of(CUTOVER, CUTOVER - 1, CUTOVER - 1),
                Arguments.of(CUTOVER, CUTOVER, CUTOVER),
                Arguments.of(CUTOVER, CUTOVER + 1, CUTOVER + 1),
                // one SHA-384 and one SHA-256 parent, as in the first rounds after the cutover
                Arguments.of(CUTOVER, CUTOVER - 1, CUTOVER),
                Arguments.of(CUTOVER, CUTOVER, CUTOVER - 1),
                // no cutover configured
                Arguments.of(Long.MAX_VALUE, CUTOVER, 1_000_000L),
                // network started with SHA-256 at genesis
                Arguments.of(0L, 1L, CUTOVER));
    }

    @ParameterizedTest(name = "cutover {0}, self parent birth round {1}, other parent birth round {2}")
    @MethodSource("validParentBirthRounds")
    @DisplayName("Test that an event with no issues passes validation")
    void successfulValidation(final long cutover, final long selfParentBirthRound, final long otherParentBirthRound) {
        EventHashFactory.initialize(cutover);

        final PlatformEvent normalEvent = new TestingEventBuilder(random)
                .setSelfParent(new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(0))
                        .setBirthRound(selfParentBirthRound)
                        .build())
                .setOtherParent(new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(1))
                        .setBirthRound(otherParentBirthRound)
                        .build())
                .build();
        final PlatformEvent missingSelfParent = new TestingEventBuilder(random)
                .setSelfParent(null)
                .setOtherParent(new TestingEventBuilder(random)
                        .setBirthRound(otherParentBirthRound)
                        .build())
                .build();
        final PlatformEvent missingOtherParent = new TestingEventBuilder(random)
                .setSelfParent(new TestingEventBuilder(random)
                        .setBirthRound(selfParentBirthRound)
                        .build())
                .setOtherParent(null)
                .build();

        assertThat(validator.isValid(normalEvent)).isTrue();
        assertThat(validator.isValid(missingSelfParent)).isTrue();
        assertThat(validator.isValid(missingOtherParent)).isTrue();
    }

    /**
     * Creates a mock event with the given parents. A real {@link PlatformEvent} cannot be built with a parent hash of
     * the wrong length, so the validator is given a mock that only supplies the gossip event.
     */
    @NonNull
    private PlatformEvent eventWithParents(@NonNull final List<EventDescriptor> parents) {
        final GossipEvent gossipEvent = new TestingEventBuilder(random)
                .setAppTransactionCount(2)
                .build()
                .getGossipEvent()
                .copyBuilder()
                .parents(parents)
                .build();
        final PlatformEvent platformEvent = Mockito.mock(PlatformEvent.class);
        when(platformEvent.getGossipEvent()).thenReturn(gossipEvent);
        return platformEvent;
    }

    @NonNull
    private EventDescriptor descriptor(final long creatorId, final long birthRound, final int hashLength) {
        final byte[] hash = new byte[hashLength];
        random.nextBytes(hash);
        return EventDescriptor.newBuilder()
                .hash(Bytes.wrap(hash))
                .creatorNodeId(creatorId)
                .birthRound(birthRound)
                .build();
    }
}
