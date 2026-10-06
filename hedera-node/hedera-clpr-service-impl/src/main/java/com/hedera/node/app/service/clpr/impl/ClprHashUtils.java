// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared utilities for CLPR running hash computation.
 */
public final class ClprHashUtils {

    private ClprHashUtils() {}

    /**
     * Computes {@code SHA-256(previousHash || SHA-256(serialized(payload)))}
     */
    @NonNull
    public static Bytes computeRunningHash(
            @NonNull final Bytes previousHash, @NonNull final ClprMessagePayload payload) {
        final var payloadHash =
                sha256(ClprMessagePayload.PROTOBUF.toBytes(payload).toByteArray());
        return computeRunningHashFromPayloadHash(previousHash, Bytes.wrap(payloadHash));
    }

    /**
     * Computes {@code SHA-256(previousHash || payloadHash)} — used on the redacted-slot
     * branch where {@code payloadHash} is read from {@code ClprRedactedMessage.message_hash}.
     */
    @NonNull
    public static Bytes computeRunningHashFromPayloadHash(
            @NonNull final Bytes previousHash, @NonNull final Bytes payloadHash) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            digest.update(previousHash.toByteArray());
            digest.update(payloadHash.toByteArray());
            return Bytes.wrap(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Returns {@code SHA-256(bytes)}. */
    @NonNull
    public static byte[] sha256(@NonNull final byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
