// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * The {@code ClprQueueMetadata} fields of a channel, as proven from the peer ledger's state. The data only: each
 * verifier decodes it from its platform's proven state (for EVM chains, see {@code ClprContractStorage}).
 *
 * @param nextMessageId outgoing-message counter
 * @param sentRunningHash the cumulative outbound running hash (32 bytes)
 * @param receivedMessageId the highest inbound message id seen
 * @param receivedRunningHash the cumulative inbound running hash (32 bytes)
 * @param status the {@code ClprChannelStatus} enum ordinal (0=PENDING,…,5=CLOSED)
 * @param lastMessageRunningHash the {@code runningHashAfterProcessing} of the last queued
 *     outbound message
 * @param endpointManifestVersion the peer Channel's {@code endpoint_manifest_version} — the sender's cached view of
 *     our endpoint-manifest version (ClprQueueMetadata proto field 7).
 *     Proven (never taken from relayed content), so a relay cannot inflate it to make the peer look
 *     up-to-date and suppress our manifest re-pushes (spec §4.5, a liveness guarantee)
 */
public record ProvenQueueMetadata(
        long nextMessageId,
        @NonNull byte[] sentRunningHash,
        long receivedMessageId,
        @NonNull byte[] receivedRunningHash,
        int status,
        @NonNull byte[] lastMessageRunningHash,
        long endpointManifestVersion) {

    private static final int HASH_LENGTH = 32;

    public ProvenQueueMetadata {
        sentRunningHash = checkedHashCopy(sentRunningHash, "sentRunningHash");
        receivedRunningHash = checkedHashCopy(receivedRunningHash, "receivedRunningHash");
        lastMessageRunningHash = checkedHashCopy(lastMessageRunningHash, "lastMessageRunningHash");
    }

    /**
     * Returns a defensive copy of {@code hash}, requiring it to be {@value #HASH_LENGTH} bytes.
     *
     * @throws IllegalArgumentException if the length differs
     */
    @NonNull
    private static byte[] checkedHashCopy(@NonNull final byte[] hash, @NonNull final String name) {
        Objects.requireNonNull(hash, name);
        if (hash.length != HASH_LENGTH) {
            throw new IllegalArgumentException(name + " must be " + HASH_LENGTH + " bytes, got " + hash.length);
        }
        return hash.clone();
    }

    /**
     * The all-zero "absent" sentinel for a bundle that carries no queue storage proof — a bundle that advances
     * only the endpoint manifest (spec §8.1.4). {@code nextMessageId == 0} is the metadata-absent sentinel (a
     * real queue's {@code nextMessageId} is always {@code >= 1}); the three running hashes are 32 zero-bytes, and the
     * manifest version is 0.
     */
    @NonNull
    public static ProvenQueueMetadata absent() {
        return new ProvenQueueMetadata(
                0L, new byte[HASH_LENGTH], 0L, new byte[HASH_LENGTH], 0, new byte[HASH_LENGTH], 0L);
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
