// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.checkedCopy;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
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
 * @param endpointManifestVersion the peer Channel's {@code endpoint_manifest_version} (SC-189 Channel
 *     offset 16) — the sender's cached view of our endpoint-manifest version (QueueMetadata proto field 7).
 *     Proven (never taken from relayed content), so a relay cannot inflate it to make the peer look
 *     up-to-date and suppress our manifest re-pushes (spec §4.5, a liveness guarantee)
 */
public record QueueMetadata(
        long nextMessageId,
        @NonNull byte[] sentRunningHash,
        long receivedMessageId,
        @NonNull byte[] receivedRunningHash,
        int status,
        @NonNull byte[] lastMessageRunningHash,
        long endpointManifestVersion) {

    /**
     * Channel-struct slots proven per bundle: {@code keccak(connId,15)+{1,2,4,5,16}} — status|nextMsgId,
     * acked|received|reply, sentRunningHash, receivedRunningHash, endpointManifestVersion. Mirrors the
     * relay's {@code ClprServiceStorageLayout} / the on-chain {@code ClprEvmBundleVerifier}.
     */
    static final int CHANNEL_SLOTS = 5;

    /** Number of proven slots for an ACK-only bundle (the five Channel slots, no message running hash). */
    static final int EXPECTED_SLOTS = CHANNEL_SLOTS;

    /** Number of proven slots for a message-bearing bundle (the five Channel slots + last-message hash). */
    static final int EXPECTED_SLOTS_WITH_MESSAGE = CHANNEL_SLOTS + 1;

    /**
     * Span (max offset − min offset) of the five Channel slots: {@code 16 − 1 = 15}. The last-message
     * running-hash slot is a far keccak outlier, so the Channel slots are the 5-slot window whose
     * key span equals this value.
     */
    private static final BigInteger CHANNEL_SLOT_CLUSTER_SPAN = BigInteger.valueOf(15);

    // Positions inside the reordered (canonical) slot-value array.
    private static final int SP_INDEX_CONN_STATUS_NEXTMSGID = 0;
    private static final int SP_INDEX_CONN_RECEIVED_MSG_ID = 1;
    private static final int SP_INDEX_CONN_SENT_RUNNING_HASH = 2;
    private static final int SP_INDEX_CONN_RECEIVED_RUNNING_HASH = 3;
    private static final int SP_INDEX_CONN_ENDPOINT_MANIFEST_VERSION = 4;
    private static final int SP_INDEX_LAST_MSG_RUNNING_HASH = 5; // optional

    public QueueMetadata {
        sentRunningHash = checkedCopy(sentRunningHash, 32, "sentRunningHash");
        receivedRunningHash = checkedCopy(receivedRunningHash, 32, "receivedRunningHash");
        lastMessageRunningHash = checkedCopy(lastMessageRunningHash, 32, "lastMessageRunningHash");
    }

    /**
     * Decode a {@link QueueMetadata} from the {@value #EXPECTED_SLOTS} Channel slots (ACK-only) or
     * {@value #EXPECTED_SLOTS_WITH_MESSAGE} slots (with the last-message running hash), given the proven
     * slot keys and values in key-sorted (ascending) order.
     *
     * <p>The five Channel slots {@code base+{1,2,4,5,16}} cluster within a 16-wide window (span 15); the
     * last-message running-hash slot is a far keccak outlier. We locate the cluster by that span rather
     * than by fixed position, because the outlier can sort before or after it. Within the ascending
     * cluster the fields sit at fixed offsets: 0 = {@code status|nextMsgId}, 1 = {@code acked|received},
     * 2 = {@code sentRunningHash}, 3 = {@code receivedRunningHash}, 4 = {@code endpointManifestVersion}.
     *
     * <p>Solidity packs primitive fields starting at the LSB of a slot, so on a 32-byte big-endian
     * storage word the first-declared field sits at the right (high index of the byte array).
     */
    @NonNull
    static QueueMetadata decode(@NonNull final byte[][] slotKeys, @NonNull final byte[][] provenSlotValues) {
        Objects.requireNonNull(slotKeys, "slotKeys");
        Objects.requireNonNull(provenSlotValues, "provenSlotValues");
        final byte[][] ordered = reorderChannelSlots(slotKeys, provenSlotValues);

        // Slot +1: verifier(20) | status(1) | nextMessageId(8) — first declared field at LSB.
        // Byte layout (MSB→LSB): 3B padding | 8B nextMessageId | 1B status | 20B verifier.
        final byte[] statusSlot = checkedCopy(ordered[SP_INDEX_CONN_STATUS_NEXTMSGID], 32, "connStatusNextMsgIdSlot");
        final long nextMessageId = readUint64BigEndian(statusSlot, 3);
        final int status = statusSlot[11] & 0xFF;

        // Slot +2: ackedMessageId(8) | receivedMessageId(8) | nextExpectedReplyId(8) — first at LSB.
        // Byte layout (MSB→LSB): 8B padding | 8B nextExpectedReplyId | 8B receivedMessageId | 8B ackedMessageId.
        final byte[] receivedIdSlot = checkedCopy(ordered[SP_INDEX_CONN_RECEIVED_MSG_ID], 32, "connReceivedMsgIdSlot");
        final long receivedMessageId = readUint64BigEndian(receivedIdSlot, 16);

        final byte[] sentRunningHash = checkedCopy(ordered[SP_INDEX_CONN_SENT_RUNNING_HASH], 32, "sentRunningHash");
        final byte[] receivedRunningHash =
                checkedCopy(ordered[SP_INDEX_CONN_RECEIVED_RUNNING_HASH], 32, "receivedRunningHash");

        // Slot +16: endpointManifestVersion (uint64) — a lone field in its own slot, LSB-packed (last 8 bytes).
        final byte[] manifestVersionSlot =
                checkedCopy(ordered[SP_INDEX_CONN_ENDPOINT_MANIFEST_VERSION], 32, "endpointManifestVersionSlot");
        final long endpointManifestVersion = readUint64BigEndian(manifestVersionSlot, 24);

        // The last-message running hash is present only for message-bearing bundles.
        final byte[] lastMsgRunningHash = ordered.length > SP_INDEX_LAST_MSG_RUNNING_HASH
                ? checkedCopy(ordered[SP_INDEX_LAST_MSG_RUNNING_HASH], 32, "lastMessageRunningHash")
                : new byte[32];

        return new QueueMetadata(
                nextMessageId,
                sentRunningHash,
                receivedMessageId,
                receivedRunningHash,
                status,
                lastMsgRunningHash,
                endpointManifestVersion);
    }

    /**
     * Reorders the key-sorted proven slots into the canonical layout {@link #decode} expects:
     * {@code [status|nextMsgId, acked|received, sentRunningHash, receivedRunningHash,
     * endpointManifestVersion]} and, for a message bundle, the last-message running hash appended.
     */
    @NonNull
    private static byte[][] reorderChannelSlots(
            @NonNull final byte[][] slotKeys, @NonNull final byte[][] provenSlotValues) {
        final int n = slotKeys.length;
        if (n != provenSlotValues.length) {
            throw EthProofs.fail("slot key/value count mismatch: " + n + " vs " + provenSlotValues.length);
        }
        final boolean hasMessage = n == EXPECTED_SLOTS_WITH_MESSAGE;
        if (n != EXPECTED_SLOTS && !hasMessage) {
            throw EthProofs.fail("expected " + EXPECTED_SLOTS + " or " + EXPECTED_SLOTS_WITH_MESSAGE
                    + " proven slot values, got " + n);
        }
        // Locate the 5-slot Channel cluster (span 15). With no outlier (ACK-only) it starts at 0;
        // otherwise the outlier is the first or last sorted entry, so exactly one of the two candidate
        // 5-windows has the cluster span.
        int clusterStart = 0;
        if (hasMessage) {
            final BigInteger spanFromZero =
                    unsigned(slotKeys[CHANNEL_SLOTS - 1]).subtract(unsigned(slotKeys[0]));
            clusterStart = spanFromZero.equals(CHANNEL_SLOT_CLUSTER_SPAN) ? 0 : 1;
            final BigInteger clusterSpan =
                    unsigned(slotKeys[clusterStart + CHANNEL_SLOTS - 1]).subtract(unsigned(slotKeys[clusterStart]));
            if (!clusterSpan.equals(CHANNEL_SLOT_CLUSTER_SPAN)) {
                throw EthProofs.fail("could not locate the Channel storage-slot cluster (span " + clusterSpan + ")");
            }
        }
        final byte[][] out = new byte[hasMessage ? EXPECTED_SLOTS_WITH_MESSAGE : EXPECTED_SLOTS][];
        out[SP_INDEX_CONN_STATUS_NEXTMSGID] = provenSlotValues[clusterStart];
        out[SP_INDEX_CONN_RECEIVED_MSG_ID] = provenSlotValues[clusterStart + 1];
        out[SP_INDEX_CONN_SENT_RUNNING_HASH] = provenSlotValues[clusterStart + 2];
        out[SP_INDEX_CONN_RECEIVED_RUNNING_HASH] = provenSlotValues[clusterStart + 3];
        out[SP_INDEX_CONN_ENDPOINT_MANIFEST_VERSION] = provenSlotValues[clusterStart + 4];
        if (hasMessage) {
            final int msgHashIdx = clusterStart == 0 ? CHANNEL_SLOTS : 0; // the entry outside the cluster
            out[SP_INDEX_LAST_MSG_RUNNING_HASH] = provenSlotValues[msgHashIdx];
        }
        return out;
    }

    /**
     * The all-zero "absent" sentinel for a bundle that carries no queue storage proof — a bundle that advances
     * only the endpoint manifest (spec §8.1.4). {@code nextMessageId == 0} is the metadata-absent sentinel (a
     * real queue's {@code nextMessageId} is always {@code >= 1}); {@link VerifiedBundle} requires non-null hashes,
     * so the three running hashes are 32 zero-bytes, and the manifest version is 0.
     */
    @NonNull
    static QueueMetadata absent() {
        return new QueueMetadata(0L, new byte[32], 0L, new byte[32], 0, new byte[32], 0L);
    }

    /** Reads 8 bytes from {@code buf} starting at {@code offset} as a big-endian unsigned long. */
    private static long readUint64BigEndian(final byte[] buf, final int offset) {
        return ByteBuffer.wrap(buf, offset, 8).getLong();
    }

    /** Interprets a storage-slot key as an unsigned big-endian integer. */
    private static BigInteger unsigned(@NonNull final byte[] key) {
        return new BigInteger(1, key);
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
