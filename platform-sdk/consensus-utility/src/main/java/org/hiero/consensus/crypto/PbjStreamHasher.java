// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.HashingOutputStream;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.transaction.TransactionWrapper;

/**
 * Hashes the PBJ representation of an event. This hasher double hashes each transaction in order to allow redaction of
 * transactions without invalidating the event hash.
 */
public class PbjStreamHasher implements EventHasher {

    /**
     * THROWAWAY PROBE for #27940, never merged: how the hasher feeds the digests, from the system property
     * {@code probe.hasher}. It is a constant, so the JIT compiler removes the modes that are not selected.
     * <ul>
     *   <li>{@code stream} (default): the production code, through {@link WritableStreamingData} and
     *   {@link HashingOutputStream};</li>
     *   <li>{@code digest}: through {@link WritableMessageDigest}, which writes straight into the digest;</li>
     *   <li>{@code buffer}: the event's core and parents are written into a reusable buffer, which the digest gets
     *   in one piece.</li>
     * </ul>
     */
    private static final int MODE = switch (System.getProperty("probe.hasher", "stream")) {
        case "stream" -> 0;
        case "digest" -> 1;
        case "buffer" -> 2;
        default -> throw new IllegalArgumentException("Unknown probe.hasher");
    };

    private final MessageDigest sha256EventDigest = DigestType.SHA_256.buildDigest();
    private final MessageDigest sha384EventDigest = DigestType.SHA_384.buildDigest();

    private final WritableSequentialData sha256EventStream =
            new WritableStreamingData(new HashingOutputStream(sha256EventDigest));
    private final WritableSequentialData sha384EventStream =
            new WritableStreamingData(new HashingOutputStream(sha384EventDigest));

    /** The hashing stream for the transactions. */
    private final MessageDigest transactionDigest = DigestType.SHA_384.buildDigest();

    final WritableSequentialData transactionStream =
            new WritableStreamingData(new HashingOutputStream(transactionDigest));

    /** Mode {@code digest}: writers straight into the digests above. */
    private final WritableSequentialData sha256EventWriter = new WritableMessageDigest(sha256EventDigest);

    private final WritableSequentialData sha384EventWriter = new WritableMessageDigest(sha384EventDigest);
    private final WritableSequentialData transactionWriter = new WritableMessageDigest(transactionDigest);

    /** Mode {@code buffer}: the array that takes the event's core and parents, and its PBJ view. */
    private byte[] bufferArray = new byte[1024];

    private BufferedData buffer = BufferedData.wrap(bufferArray);

    @Override
    @NonNull
    public PlatformEvent hashEvent(@NonNull final PlatformEvent event) {
        Objects.requireNonNull(event);
        final Hash hash = hashEvent(event.getEventCore(), event.getGossipEvent().parents(), event.getTransactions());
        event.setHash(hash);
        return event;
    }

    /**
     * Hashes the given {@link UnsignedEvent} and sets the hash on the event.
     *
     * @param event the event to hash
     */
    public void hashUnsignedEvent(@NonNull final UnsignedEvent event) {
        final List<EventDescriptor> parentDescriptors =
                event.getParents().stream().map(EventDescriptorWrapper::toPbj).toList();
        final Hash hash = hashEvent(event.getEventCore(), parentDescriptors, event.getTransactions());
        event.setHash(hash);
    }

    /**
     * Hashes the given event and returns the hash.
     *
     * @param eventCore    the event to hash
     * @param parents      the parents of the event
     * @param transactions the transactions to hash
     * @return the hash of the event
     */
    @NonNull
    private Hash hashEvent(
            @NonNull final EventCore eventCore,
            @NonNull final List<EventDescriptor> parents,
            @NonNull final List<TransactionWrapper> transactions) {
        if (MODE == 1) {
            return hashEventWithDigestWriter(eventCore, parents, transactions);
        }
        if (MODE == 2) {
            return hashEventWithBuffer(eventCore, parents, transactions);
        }
        return hashEventWithStream(eventCore, parents, transactions);
    }

    /** Mode {@code stream}: the production code, unchanged. */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    @NonNull
    private Hash hashEventWithStream(
            @NonNull final EventCore eventCore,
            @NonNull final List<EventDescriptor> parents,
            @NonNull final List<TransactionWrapper> transactions) {
        boolean success = false;

        final DigestType digestType;
        final MessageDigest eventDigest;
        final WritableSequentialData eventStream;
        if (EventHashFactory.isBirthRoundPostCutover(eventCore.birthRound())) {
            digestType = DigestType.SHA_256;
            eventDigest = sha256EventDigest;
            eventStream = sha256EventStream;
        } else {
            digestType = DigestType.SHA_384;
            eventDigest = sha384EventDigest;
            eventStream = sha384EventStream;
        }

        try {
            EventCore.PROTOBUF.write(eventCore, eventStream);
            for (int i = 0, parentsSize = parents.size(); i < parentsSize; i++) {
                EventDescriptor.PROTOBUF.write(parents.get(i), eventStream);
            }
            for (int i = 0, transactionsSize = transactions.size(); i < transactionsSize; i++) {
                final TransactionWrapper transaction = transactions.get(i);
                transactionStream.writeBytes(Objects.requireNonNull(transaction.getApplicationTransaction()));
                processTransactionHash(eventStream, transaction);
            }
            success = true;
        } catch (final IOException e) {
            throw new RuntimeException("An exception occurred while trying to hash an event!", e);
        } finally {
            if (!success) {
                transactionDigest.reset();
                eventDigest.reset();
            }
        }

        return new Hash(eventDigest.digest(), digestType);
    }

    private void processTransactionHash(
            final WritableSequentialData eventStream, final TransactionWrapper transaction) {
        final byte[] hash = transactionDigest.digest();
        transaction.setHash(Bytes.wrap(hash));
        eventStream.writeBytes(hash);
    }

    /** Mode {@code digest}: the production code with writers that write straight into the digests. */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    @NonNull
    private Hash hashEventWithDigestWriter(
            @NonNull final EventCore eventCore,
            @NonNull final List<EventDescriptor> parents,
            @NonNull final List<TransactionWrapper> transactions) {
        boolean success = false;

        final DigestType digestType;
        final MessageDigest eventDigest;
        final WritableSequentialData eventWriter;
        if (EventHashFactory.isBirthRoundPostCutover(eventCore.birthRound())) {
            digestType = DigestType.SHA_256;
            eventDigest = sha256EventDigest;
            eventWriter = sha256EventWriter;
        } else {
            digestType = DigestType.SHA_384;
            eventDigest = sha384EventDigest;
            eventWriter = sha384EventWriter;
        }

        try {
            EventCore.PROTOBUF.write(eventCore, eventWriter);
            for (int i = 0, parentsSize = parents.size(); i < parentsSize; i++) {
                EventDescriptor.PROTOBUF.write(parents.get(i), eventWriter);
            }
            for (int i = 0, transactionsSize = transactions.size(); i < transactionsSize; i++) {
                final TransactionWrapper transaction = transactions.get(i);
                transactionWriter.writeBytes(Objects.requireNonNull(transaction.getApplicationTransaction()));
                processTransactionHash(eventWriter, transaction);
            }
            success = true;
        } catch (final IOException e) {
            throw new RuntimeException("An exception occurred while trying to hash an event!", e);
        } finally {
            if (!success) {
                transactionDigest.reset();
                eventDigest.reset();
            }
        }

        return new Hash(eventDigest.digest(), digestType);
    }

    /**
     * Mode {@code buffer}: writes the event's core and parents into the buffer and gives them to the digest in one
     * piece, followed by the hash of each transaction.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    @NonNull
    private Hash hashEventWithBuffer(
            @NonNull final EventCore eventCore,
            @NonNull final List<EventDescriptor> parents,
            @NonNull final List<TransactionWrapper> transactions) {
        boolean success = false;

        final DigestType digestType;
        final MessageDigest eventDigest;
        if (EventHashFactory.isBirthRoundPostCutover(eventCore.birthRound())) {
            digestType = DigestType.SHA_256;
            eventDigest = sha256EventDigest;
        } else {
            digestType = DigestType.SHA_384;
            eventDigest = sha384EventDigest;
        }

        try {
            writeToBuffer(eventCore, parents);
            eventDigest.update(bufferArray, 0, (int) buffer.position());
            for (int i = 0, transactionsSize = transactions.size(); i < transactionsSize; i++) {
                final TransactionWrapper transaction = transactions.get(i);
                Objects.requireNonNull(transaction.getApplicationTransaction()).writeTo(transactionDigest);
                final byte[] hash = transactionDigest.digest();
                transaction.setHash(Bytes.wrap(hash));
                eventDigest.update(hash);
            }
            success = true;
        } catch (final IOException e) {
            throw new RuntimeException("An exception occurred while trying to hash an event!", e);
        } finally {
            if (!success) {
                transactionDigest.reset();
                eventDigest.reset();
            }
        }

        return new Hash(eventDigest.digest(), digestType);
    }

    /**
     * Writes the event's core and parents into the buffer, from its start. A buffer that is too small is replaced by
     * one of twice the size, and the writing starts again.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    private void writeToBuffer(@NonNull final EventCore eventCore, @NonNull final List<EventDescriptor> parents)
            throws IOException {
        while (true) {
            buffer.reset();
            try {
                EventCore.PROTOBUF.write(eventCore, buffer);
                for (int i = 0, parentsSize = parents.size(); i < parentsSize; i++) {
                    EventDescriptor.PROTOBUF.write(parents.get(i), buffer);
                }
                return;
            } catch (final BufferOverflowException e) {
                bufferArray = new byte[bufferArray.length * 2];
                buffer = BufferedData.wrap(bufferArray);
            }
        }
    }
}
