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
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class PbjStreamHasherTest {

    private static final Random RANDOM = RandomUtils.getRandomPrintSeed();

    /** The first birth round hashed with SHA-256. */
    private static final long CUTOVER = 100;

    @ParameterizedTest(name = "birth round {0} -> {1}")
    @CsvSource({"99, SHA_384", "100, SHA_256"})
    void eventWithoutTransactions(final long birthRound, final DigestType expectedType) {
        final PlatformEvent event = new TestingEventBuilder(RANDOM)
                .setBirthRound(birthRound)
                .setAppTransactionCount(0)
                .build();

        new PbjStreamHasher().hashEvent(event);

        assertThat(event.getHash()).isEqualTo(expectedHash(event, expectedType));
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

    @Test
    void eventLargerThanTheInitialBuffer() {
        final PbjStreamHasher hasher = new PbjStreamHasher();

        // the hashes of 100 parents alone take more than 3 KB; the hasher's buffer starts with 1 KB
        final PlatformEvent largeEvent = new TestingEventBuilder(RANDOM)
                .setBirthRound(CUTOVER + 1)
                .setSelfParent(hashedEventWithBirthRound(hasher, CUTOVER))
                .setOtherParents(Stream.generate(() -> hashedEventWithBirthRound(hasher, CUTOVER))
                        .limit(100)
                        .toList())
                .setAppTransactionCount(3)
                .build();
        final PlatformEvent smallEvent = eventWithBirthRound(CUTOVER + 1);

        hasher.hashEvent(largeEvent);
        hasher.hashEvent(smallEvent);

        assertThat(largeEvent.getHash()).isEqualTo(expectedHash(largeEvent, DigestType.SHA_256));
        assertThat(smallEvent.getHash()).isEqualTo(expectedHash(smallEvent, DigestType.SHA_256));
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
