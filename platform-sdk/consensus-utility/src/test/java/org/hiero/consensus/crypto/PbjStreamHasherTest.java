// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PbjStreamHasherTest {

    private static final Random RANDOM = RandomUtils.getRandomPrintSeed();

    /** The first birth round hashed with SHA-256. */
    private static final long CUTOVER = 100;

    @AfterEach
    void tearDown() {
        // restore the default so tests do not leak static state
        EventHashFactory.initialize(Long.MAX_VALUE);
    }

    static Stream<Arguments> digestTypeSelection() {
        return Stream.of(
                // cutover configured
                Arguments.of(CUTOVER, CUTOVER - 1, DigestType.SHA_384),
                Arguments.of(CUTOVER, CUTOVER, DigestType.SHA_256),
                Arguments.of(CUTOVER, CUTOVER + 1, DigestType.SHA_256),
                // no cutover configured
                Arguments.of(Long.MAX_VALUE, 1L, DigestType.SHA_384),
                Arguments.of(Long.MAX_VALUE, CUTOVER, DigestType.SHA_384),
                Arguments.of(Long.MAX_VALUE, 1_000_000L, DigestType.SHA_384),
                // network started with SHA-256 at genesis
                Arguments.of(0L, 1L, DigestType.SHA_256),
                Arguments.of(0L, CUTOVER, DigestType.SHA_256),
                Arguments.of(0L, 1_000_000L, DigestType.SHA_256));
    }

    @ParameterizedTest(name = "cutover {0}, birth round {1} -> {2}")
    @MethodSource("digestTypeSelection")
    void digestTypeDependsOnBirthRound(final long cutover, final long birthRound, final DigestType expectedType) {
        EventHashFactory.initialize(cutover);
        final PlatformEvent event = eventWithBirthRound(birthRound);

        new PbjStreamHasher().hashEvent(event);

        assertThat(event.getHash()).isEqualTo(expectedHash(event, expectedType));
    }

    @ParameterizedTest(name = "birth round {0} -> {1}")
    @CsvSource({"99, SHA_384", "100, SHA_256"})
    void eventWithoutTransactions(final long birthRound, final DigestType expectedType) {
        EventHashFactory.initialize(CUTOVER);
        final PlatformEvent event = new TestingEventBuilder(RANDOM)
                .setBirthRound(birthRound)
                .setAppTransactionCount(0)
                .build();

        new PbjStreamHasher().hashEvent(event);

        assertThat(event.getHash()).isEqualTo(expectedHash(event, expectedType));
    }

    @Test
    void sameEventHashesDifferentlyEitherSideOfCutover() {
        final PlatformEvent event = eventWithBirthRound(CUTOVER);
        final PbjStreamHasher hasher = new PbjStreamHasher();

        EventHashFactory.initialize(CUTOVER + 1);
        final Hash preCutoverHash = hasher.hashEvent(event).getHash();

        EventHashFactory.initialize(CUTOVER);
        final Hash postCutoverHash = hasher.hashEvent(event).getHash();

        assertThat(preCutoverHash.getDigestType()).isEqualTo(DigestType.SHA_384);
        assertThat(postCutoverHash.getDigestType()).isEqualTo(DigestType.SHA_256);
        assertThat(preCutoverHash.getBytes()).isNotEqualTo(postCutoverHash.getBytes());
    }

    @ParameterizedTest(name = "birth round {0}")
    @ValueSource(longs = {CUTOVER - 1, CUTOVER})
    void transactionHashesAreAlwaysSha384(final long birthRound) {
        // transaction hashes do not change algorithm at the cutover, only the event hash does
        EventHashFactory.initialize(CUTOVER);
        final PlatformEvent event = eventWithBirthRound(birthRound);

        new PbjStreamHasher().hashEvent(event);

        assertThat(event.getTransactions()).isNotEmpty();
        for (final TransactionWrapper transaction : event.getTransactions()) {
            assertThat(transaction.getHash()).isEqualTo(Bytes.wrap(sha384(transaction.getApplicationTransaction())));
        }
    }

    @Test
    void alternatingAlgorithmsMatchFreshHasher() {
        EventHashFactory.initialize(CUTOVER);
        final PbjStreamHasher reusedHasher = new PbjStreamHasher();

        final PlatformEvent preCutover = eventWithBirthRound(CUTOVER - 1);
        final PlatformEvent postCutover = eventWithBirthRound(CUTOVER);
        final Hash expectedPreCutover =
                new PbjStreamHasher().hashEvent(preCutover).getHash();
        final Hash expectedPostCutover =
                new PbjStreamHasher().hashEvent(postCutover).getHash();

        // switching back and forth must not leak digest state from one algorithm into the other
        for (int i = 0; i < 3; i++) {
            assertThat(reusedHasher.hashEvent(preCutover).getHash()).isEqualTo(expectedPreCutover);
            assertThat(reusedHasher.hashEvent(postCutover).getHash()).isEqualTo(expectedPostCutover);
        }
    }

    /** Ways to make hashing fail part way through an event. */
    enum Failure {
        /** A transaction that returns null fails after the event core and parents are written. */
        TRANSACTION,
        /** A null parent fails after the event core is written, before the transaction digest is used. */
        PARENT
    }

    static Stream<Arguments> failureRecovery() {
        return Stream.of(Failure.values())
                .flatMap(failure -> Stream.of(
                        // same algorithm for the failed and the valid event
                        Arguments.of(CUTOVER - 1, CUTOVER - 1, failure),
                        Arguments.of(CUTOVER, CUTOVER, failure),
                        // a failure with one algorithm followed by a valid event with the other
                        Arguments.of(CUTOVER - 1, CUTOVER, failure),
                        Arguments.of(CUTOVER, CUTOVER - 1, failure)));
    }

    @ParameterizedTest(name = "valid birth round {0}, failed birth round {1}, {2} failure")
    @MethodSource("failureRecovery")
    void reusableAfterException(final long validBirthRound, final long failedBirthRound, final Failure failure) {
        EventHashFactory.initialize(CUTOVER);
        final PbjStreamHasher hasher = new PbjStreamHasher();

        // 1. Hash a valid event and record the expected hash
        final PlatformEvent validEvent = eventWithBirthRound(validBirthRound);
        final Hash expectedHash = hasher.hashEvent(validEvent).getHash();

        // 2. Fail part way through hashing another event, dirtying the digests
        final PlatformEvent badEvent = badEvent(failedBirthRound, failure);
        assertThatThrownBy(() -> hasher.hashEvent(badEvent)).isInstanceOf(NullPointerException.class);

        // 3. Hash the valid event again — must produce the same hash, proving the digests were reset
        assertThat(hasher.hashEvent(validEvent).getHash()).isEqualTo(expectedHash);
    }

    static Stream<Arguments> mixedParents() {
        return Stream.of(
                // the first events after the upgrade have only pre-cutover parents
                Arguments.of(CUTOVER - 1, List.of(CUTOVER - 1), List.of(48, 48)),
                Arguments.of(CUTOVER - 1, List.of(CUTOVER), List.of(48, 32)),
                Arguments.of(CUTOVER, List.of(CUTOVER - 1), List.of(32, 48)),
                Arguments.of(
                        CUTOVER, List.of(CUTOVER - 1, CUTOVER, CUTOVER - 2, CUTOVER + 1), List.of(32, 48, 32, 48, 32)));
    }

    @ParameterizedTest(name = "self parent birth round {0}, other parent birth rounds {1}")
    @MethodSource("mixedParents")
    void postCutoverEventWithMixedParentHashTypesIsSha256(
            final long selfParentBirthRound,
            final List<Long> otherParentBirthRounds,
            final List<Integer> expectedParentHashLengths) {
        EventHashFactory.initialize(CUTOVER);
        final PbjStreamHasher hasher = new PbjStreamHasher();

        final PlatformEvent child = new TestingEventBuilder(RANDOM)
                .setBirthRound(CUTOVER + 1)
                .setSelfParent(hashedEventWithBirthRound(hasher, selfParentBirthRound))
                .setOtherParents(otherParentBirthRounds.stream()
                        .map(birthRound -> hashedEventWithBirthRound(hasher, birthRound))
                        .toList())
                .build();

        hasher.hashEvent(child);

        assertThat(parentHashLengths(child)).isEqualTo(expectedParentHashLengths);
        assertThat(child.getHash()).isEqualTo(expectedHash(child, DigestType.SHA_256));
    }

    @ParameterizedTest(name = "birth round {0}")
    @ValueSource(longs = {CUTOVER - 1, CUTOVER})
    void hashUnsignedEventMatchesHashEvent(final long birthRound) {
        EventHashFactory.initialize(CUTOVER);
        final PbjStreamHasher hasher = new PbjStreamHasher();

        final PlatformEvent event = new TestingEventBuilder(RANDOM)
                .setBirthRound(birthRound)
                .setSelfParent(hashedEventWithBirthRound(hasher, CUTOVER - 1))
                .setOtherParent(hashedEventWithBirthRound(hasher, CUTOVER))
                .setAppTransactionCount(3)
                .build();
        final UnsignedEvent unsignedEvent = new UnsignedEvent(
                event.getCreatorId(),
                event.getAllParents(),
                event.getBirthRound(),
                event.getTimeCreated(),
                event.getGossipEvent().transactions(),
                event.getEventCore().coin());

        hasher.hashEvent(event);
        hasher.hashUnsignedEvent(unsignedEvent);

        // self events are hashed with hashUnsignedEvent, so other nodes must get the same hash with hashEvent
        assertThat(unsignedEvent.getHash()).isEqualTo(event.getHash());
    }

    /**
     * Creates an event that makes the hasher throw part way through hashing.
     */
    @NonNull
    private static PlatformEvent badEvent(final long birthRound, @NonNull final Failure failure) {
        final PlatformEvent sourceEvent = eventWithBirthRound(birthRound);
        final PlatformEvent badEvent = mock(PlatformEvent.class);
        when(badEvent.getEventCore()).thenReturn(sourceEvent.getEventCore());

        switch (failure) {
            case TRANSACTION -> {
                final TransactionWrapper poison = mock(TransactionWrapper.class);
                when(poison.getApplicationTransaction()).thenReturn(null);
                when(badEvent.getGossipEvent()).thenReturn(sourceEvent.getGossipEvent());
                when(badEvent.getTransactions()).thenReturn(List.of(poison));
            }
            case PARENT -> {
                final ArrayList<EventDescriptor> parents = new ArrayList<>();
                parents.add(null);
                final GossipEvent poisonGossip = new GossipEvent(
                        sourceEvent.getEventCore(),
                        sourceEvent.getSignature(),
                        sourceEvent.getGossipEvent().transactions(),
                        parents);
                when(badEvent.getGossipEvent()).thenReturn(poisonGossip);
                when(badEvent.getTransactions()).thenReturn(sourceEvent.getTransactions());
            }
        }
        return badEvent;
    }

    @NonNull
    private static PlatformEvent eventWithBirthRound(final long birthRound) {
        return new TestingEventBuilder(RANDOM)
                .setBirthRound(birthRound)
                .setAppTransactionCount(3)
                .build();
    }

    @NonNull
    private static PlatformEvent hashedEventWithBirthRound(
            @NonNull final PbjStreamHasher hasher, final long birthRound) {
        return hasher.hashEvent(eventWithBirthRound(birthRound));
    }

    @NonNull
    private static List<Integer> parentHashLengths(@NonNull final PlatformEvent event) {
        return event.getGossipEvent().parents().stream()
                .map(parent -> (int) parent.hash().length())
                .toList();
    }

    /**
     * Computes the expected event hash independently of {@link PbjStreamHasher}: the event core, then each parent
     * descriptor, then the SHA-384 hash of each transaction, all hashed with {@code digestType}.
     */
    @NonNull
    private static Hash expectedHash(@NonNull final PlatformEvent event, @NonNull final DigestType digestType) {
        final MessageDigest digest = digestType.buildDigest();
        digest.update(EventCore.PROTOBUF.toBytes(event.getEventCore()).toByteArray());
        for (final EventDescriptor parent : event.getGossipEvent().parents()) {
            digest.update(EventDescriptor.PROTOBUF.toBytes(parent).toByteArray());
        }
        for (final Bytes transaction : event.getGossipEvent().transactions()) {
            digest.update(sha384(transaction));
        }
        return new Hash(digest.digest(), digestType);
    }

    @NonNull
    private static byte[] sha384(@NonNull final Bytes bytes) {
        return DigestType.SHA_384.buildDigest().digest(bytes.toByteArray());
    }
}
