// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.model.event.EventConstants.DEFAULT_EVENT_DIGEST_TYPE;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class PbjStreamHasherTest {

    private static final Random RANDOM = RandomUtils.getRandomPrintSeed();

    @ParameterizedTest(name = "birth round {0} -> {1}")
    @CsvSource({"99, SHA_384", "100, SHA_256"})
    void eventWithoutTransactions(final long birthRound, final DigestType expectedType) {
        final PlatformEvent event = new TestingEventBuilder(RANDOM)
                .setBirthRound(birthRound)
                .setAppTransactionCount(0)
                .build();

        new PbjStreamHasher().hashEvent(event);

        assertThat(event.getHash()).isEqualTo(expectedHash(event));
    }

    /** Ways to make hashing fail part way through an event. */
    enum Failure {
        /** A transaction that returns null fails after the event core and parents are written. */
        TRANSACTION,
        /** A null parent fails after the event core is written, before the transaction digest is used. */
        PARENT
    }

    @ParameterizedTest(name = "valid birth round {0}, failed birth round {1}, {2} failure")
    @EnumSource(Failure.class)
    void reusableAfterException(final Failure failure) {
        final PbjStreamHasher hasher = new PbjStreamHasher();

        // 1. Hash a valid event and record the expected hash
        final PlatformEvent validEvent = newEvent();
        final Hash expectedHash = hasher.hashEvent(validEvent).getHash();

        // 2. Fail part way through hashing another event, dirtying the digests
        final PlatformEvent badEvent = badEvent(failure);
        assertThatThrownBy(() -> hasher.hashEvent(badEvent)).isInstanceOf(NullPointerException.class);

        // 3. Hash the valid event again — must produce the same hash, proving the digests were reset
        assertThat(hasher.hashEvent(validEvent).getHash()).isEqualTo(expectedHash);
    }

    @Test
    void eventLargerThanTheInitialBuffer() {
        final PbjStreamHasher hasher = new PbjStreamHasher();

        // the hashes of 100 parents alone take more than 3 KB; the hasher's buffer starts with 1 KB
        final PlatformEvent largeEvent = new TestingEventBuilder(RANDOM)
                .setBirthRound(RANDOM.nextLong())
                .setSelfParent(hashedEventWithBirthRound(hasher))
                .setOtherParents(Stream.generate(() -> hashedEventWithBirthRound(hasher))
                        .limit(100)
                        .toList())
                .setAppTransactionCount(3)
                .build();
        final PlatformEvent smallEvent = newEvent();

        hasher.hashEvent(largeEvent);
        hasher.hashEvent(smallEvent);

        assertThat(largeEvent.getHash()).isEqualTo(expectedHash(largeEvent));
        assertThat(smallEvent.getHash()).isEqualTo(expectedHash(smallEvent));
    }

    /**
     * Creates an event that makes the hasher throw part way through hashing.
     */
    @NonNull
    private static PlatformEvent badEvent(@NonNull final Failure failure) {
        final PlatformEvent sourceEvent = newEvent();
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
    private static PlatformEvent newEvent() {
        return new TestingEventBuilder(RANDOM).setAppTransactionCount(3).build();
    }

    @NonNull
    private static PlatformEvent hashedEventWithBirthRound(@NonNull final PbjStreamHasher hasher) {
        return hasher.hashEvent(newEvent());
    }

    /**
     * Computes the expected event hash independently of {@link PbjStreamHasher}: the event core, then each parent
     * descriptor, then the SHA-384 hash of each transaction, all hashed with {@code digestType}.
     */
    @NonNull
    private static Hash expectedHash(@NonNull final PlatformEvent event) {
        final MessageDigest digest = DEFAULT_EVENT_DIGEST_TYPE.buildDigest();
        digest.update(EventCore.PROTOBUF.toBytes(event.getEventCore()).toByteArray());
        for (final EventDescriptor parent : event.getGossipEvent().parents()) {
            digest.update(EventDescriptor.PROTOBUF.toBytes(parent).toByteArray());
        }
        for (final Bytes transaction : event.getGossipEvent().transactions()) {
            digest.update(sha384(transaction));
        }
        return new Hash(digest.digest(), DEFAULT_EVENT_DIGEST_TYPE);
    }

    @NonNull
    private static byte[] sha384(@NonNull final Bytes bytes) {
        return DigestType.SHA_384.buildDigest().digest(bytes.toByteArray());
    }
}
