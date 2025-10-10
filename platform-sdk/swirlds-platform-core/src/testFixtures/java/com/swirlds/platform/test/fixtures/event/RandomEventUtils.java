// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.crypto.test.fixtures.CryptoRandomUtils;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.TransactionWrapper;

public class RandomEventUtils {
    public static final Instant DEFAULT_FIRST_EVENT_TIME_CREATED = Instant.ofEpochMilli(1588771316678L);

    /**
     * Similar to randomEvent, but the timestamp used for the event's creation timestamp
     * is provided by an argument.
     */
    public static EventImpl randomEventWithTimestamp(
            final Random random,
            final NodeId creatorId,
            final Instant timestamp,
            final long birthRound,
            final TransactionWrapper[] transactions,
            final EventImpl selfParent,
            final EventImpl otherParent,
            final boolean fakeHash) {

        final UnsignedEvent unsignedEvent = randomUnsignedEventWithTimestamp(
                random, creatorId, timestamp, birthRound, transactions, selfParent, otherParent, fakeHash);

        final byte[] sig = new byte[SignatureType.RSA.signatureLength()];
        random.nextBytes(sig);

        return new EventImpl(new PlatformEvent(unsignedEvent, Bytes.wrap(sig)), selfParent, otherParent);
    }

    /**
     * Similar to randomEventHashedData but where the timestamp provided to this
     * method is the timestamp used as the creation timestamp for the event.
     */
    public static UnsignedEvent randomUnsignedEventWithTimestamp(
            @NonNull final Random random,
            @NonNull final NodeId creatorId,
            @NonNull final Instant timestamp,
            final long birthRound,
            @Nullable final TransactionWrapper[] transactions,
            @Nullable final EventImpl selfParent,
            @Nullable final EventImpl otherParent,
            final boolean fakeHash) {

        final EventDescriptorWrapper selfDescriptor = (selfParent == null || selfParent.getBaseHash() == null)
                ? null
                : selfParent.getBaseEvent().getDescriptor();
        final EventDescriptorWrapper otherDescriptor = (otherParent == null || otherParent.getBaseHash() == null)
                ? null
                : otherParent.getBaseEvent().getDescriptor();

        final List<Bytes> convertedTransactions = new ArrayList<>();
        if (transactions != null) {
            Stream.of(transactions)
                    .map(TransactionWrapper::getApplicationTransaction)
                    .forEach(convertedTransactions::add);
        }
        final UnsignedEvent unsignedEvent = new UnsignedEvent(
                creatorId,
                selfDescriptor,
                otherDescriptor == null ? Collections.emptyList() : Collections.singletonList(otherDescriptor),
                birthRound,
                timestamp,
                convertedTransactions,
                random.nextLong(0, Long.MAX_VALUE));

        if (fakeHash) {
            unsignedEvent.setHash(CryptoRandomUtils.randomHash(random));
        } else {
            new PbjStreamHasher().hashUnsignedEvent(unsignedEvent);
        }
        return unsignedEvent;
    }
}
