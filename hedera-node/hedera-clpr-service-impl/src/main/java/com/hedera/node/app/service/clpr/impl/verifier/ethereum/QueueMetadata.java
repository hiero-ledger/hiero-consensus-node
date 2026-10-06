// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.checkedCopy;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * The {@code ClprQueueMetadata} fields proven by the bundle's storage proofs.
 *
 * @param nextMessageId outgoing-message counter, decoded from the packed Channel slot
 * @param sentRunningHash the cumulative outbound running hash (bytes32)
 * @param receivedMessageId the highest inbound message id seen, decoded from the packed slot
 * @param receivedRunningHash the cumulative inbound running hash (bytes32)
 * @param status the {@code ClprChannelStatus} enum ordinal (0=PENDING,…,5=CLOSED)
 * @param lastMessageRunningHash the {@code runningHashAfterProcessing} of the last queued
 *     outbound message
 */
public record QueueMetadata(
        long nextMessageId,
        @NonNull byte[] sentRunningHash,
        long receivedMessageId,
        @NonNull byte[] receivedRunningHash,
        int status,
        @NonNull byte[] lastMessageRunningHash) {

    /** Number of Merkle-proven storage slots a bundle carries. */
    static final int EXPECTED_SLOTS = 5;

    private static final int SP_INDEX_CONN_STATUS_NEXTMSGID = 0;
    private static final int SP_INDEX_CONN_RECEIVED_MSG_ID = 1;
    private static final int SP_INDEX_CONN_SENT_RUNNING_HASH = 2;
    private static final int SP_INDEX_CONN_RECEIVED_RUNNING_HASH = 3;
    private static final int SP_INDEX_LAST_MSG_RUNNING_HASH = 4;

    public QueueMetadata {
        sentRunningHash = checkedCopy(sentRunningHash, 32, "sentRunningHash");
        receivedRunningHash = checkedCopy(receivedRunningHash, 32, "receivedRunningHash");
        lastMessageRunningHash = checkedCopy(lastMessageRunningHash, 32, "lastMessageRunningHash");
    }

    /**
     * Decode a {@link QueueMetadata} from the five Merkle-proven storage-slot values, in
     * slot-key (ascending) order:
     * <ol>
     *   <li>index 0 — packed slot {@code verifier(20) | status(1) | nextMessageId(8)}.</li>
     *   <li>index 1 — packed slot {@code ackedMessageId(8) | receivedMessageId(8) | nextExpectedReplyId(8)}.</li>
     *   <li>index 2 — {@code sentRunningHash} (bytes32).</li>
     *   <li>index 3 — {@code receivedRunningHash} (bytes32).</li>
     *   <li>index 4 — last queued message's {@code runningHashAfterProcessing} (bytes32).</li>
     * </ol>
     *
     * <p>Solidity packs primitive fields starting at the LSB of a slot, so on a 32-byte
     * big-endian storage word the first-declared field sits at the right (high index of the
     * byte array).
     */
    @NonNull
    static QueueMetadata decode(@NonNull final byte[][] provenSlotValues) {
        Objects.requireNonNull(provenSlotValues, "provenSlotValues");
        if (provenSlotValues.length != EXPECTED_SLOTS) {
            throw EthProofs.fail("expected " + EXPECTED_SLOTS + " proven slot values, got " + provenSlotValues.length);
        }

        final byte[] lastMsgRunningHash =
                checkedCopy(provenSlotValues[SP_INDEX_LAST_MSG_RUNNING_HASH], 32, "lastMessageRunningHash");

        // Slot 0: verifier(20) | status(1) | nextMessageId(8) — first declared field at LSB.
        // Byte layout (MSB→LSB): 3B padding | 8B nextMessageId | 1B status | 20B verifier.
        final byte[] statusSlot =
                checkedCopy(provenSlotValues[SP_INDEX_CONN_STATUS_NEXTMSGID], 32, "connStatusNextMsgIdSlot");
        final long nextMessageId = readUint64BigEndian(statusSlot, 3);
        final int status = statusSlot[11] & 0xFF;

        // Slot 1: ackedMessageId(8) | receivedMessageId(8) | nextExpectedReplyId(8) — first at LSB.
        // Byte layout (MSB→LSB): 8B padding | 8B nextExpectedReplyId | 8B receivedMessageId | 8B ackedMessageId.
        final byte[] receivedIdSlot =
                checkedCopy(provenSlotValues[SP_INDEX_CONN_RECEIVED_MSG_ID], 32, "connReceivedMsgIdSlot");
        final long receivedMessageId = readUint64BigEndian(receivedIdSlot, 16);

        final byte[] sentRunningHash =
                checkedCopy(provenSlotValues[SP_INDEX_CONN_SENT_RUNNING_HASH], 32, "sentRunningHash");
        final byte[] receivedRunningHash =
                checkedCopy(provenSlotValues[SP_INDEX_CONN_RECEIVED_RUNNING_HASH], 32, "receivedRunningHash");

        return new QueueMetadata(
                nextMessageId, sentRunningHash, receivedMessageId, receivedRunningHash, status, lastMsgRunningHash);
    }

    /**
     * The all-zero "absent" sentinel for a bundle that carries no queue storage proof — a bundle that advances
     * only the endpoint manifest (spec §8.1.4). {@code nextMessageId == 0} is the metadata-absent sentinel (a
     * real queue's {@code nextMessageId} is always {@code >= 1}); {@link VerifiedBundle} requires non-null hashes,
     * so the three running hashes are 32 zero-bytes.
     */
    @NonNull
    static QueueMetadata absent() {
        return new QueueMetadata(0L, new byte[32], 0L, new byte[32], 0, new byte[32]);
    }

    /** Reads 8 bytes from {@code buf} starting at {@code offset} as a big-endian unsigned long. */
    private static long readUint64BigEndian(final byte[] buf, final int offset) {
        return ByteBuffer.wrap(buf, offset, 8).getLong();
    }

    @Override
    public byte[] sentRunningHash() {
        return sentRunningHash.clone();
    }

    @Override
    public byte[] receivedRunningHash() {
        return receivedRunningHash.clone();
    }

    @Override
    public byte[] lastMessageRunningHash() {
        return lastMessageRunningHash.clone();
    }
}
