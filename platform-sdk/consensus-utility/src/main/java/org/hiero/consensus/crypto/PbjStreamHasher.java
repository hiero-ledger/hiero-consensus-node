// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.security.MessageDigest;
import java.util.List;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
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

    /** The initial size of the buffer, enough for the core and the parents of a typical event. */
    private static final int INITIAL_BUFFER_SIZE = 1024;

    private final MessageDigest sha256EventDigest = DigestType.SHA_256.buildDigest();
    private final MessageDigest sha384EventDigest = DigestType.SHA_384.buildDigest();

    /** The digest for the transactions. */
    private final MessageDigest transactionDigest = DigestType.SHA_384.buildDigest();

    /** The array that takes the serialized core and parents of an event. */
    private byte[] bufferArray = new byte[INITIAL_BUFFER_SIZE];

    /** The PBJ view of {@link #bufferArray}. */
    private BufferedData buffer = BufferedData.wrap(bufferArray);

    @Override
    @NonNull
    public PlatformEvent hashEvent(@NonNull final PlatformEvent event) {
        requireNonNull(event);
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
    @SuppressWarnings("ForLoopReplaceableByForEach")
    @NonNull
    private Hash hashEvent(
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
                requireNonNull(transaction.getApplicationTransaction()).writeTo(transactionDigest);
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
     * Writes the core and the parents of an event into the buffer, from its start. A buffer that is too small is
     * replaced by one of twice the size, and the writing starts again.
     *
     * @param eventCore the core of the event
     * @param parents   the parents of the event
     * @throws IOException if the serialization fails
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
