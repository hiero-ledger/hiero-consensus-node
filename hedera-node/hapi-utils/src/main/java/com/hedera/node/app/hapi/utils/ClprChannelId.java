// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.keccak256DigestOf;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Derives a direction-independent CLPR channel ID from one leg per direction and a bytes32 salt.
 *
 * <pre>
 * leg(S→D)   = keccak256(keccak256(chainId_S) ‖ keccak256(service_S) ‖ enc(configTimestamp_S)
 *                        ‖ trustAnchorHash_S ‖ keccak256(verifier_D))
 * channel_id = keccak256(min(legA, legB) ‖ max(legA, legB) ‖ salt)
 * </pre>
 *
 * where {@code enc} is {@code abi.encode(uint96 nanosSinceEpoch)} and the legs are ordered as unsigned bytes.
 */
public final class ClprChannelId {
    private static final int WORD = 32;
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    private ClprChannelId() {}

    /**
     * Returns the hash of the leg from source ledger S to destination ledger D.
     *
     * @param chainId S's CAIP-2 chain ID
     * @param service S's CLPR service address
     * @param configTimestamp the version of S's configuration that D holds
     * @param trustAnchorHash keccak256 of the initial trust anchor D holds for S
     * @param destinationVerifier the verifier on D for S
     * @return the 32-byte leg hash
     */
    public static Bytes leg(
            final String chainId,
            final Bytes service,
            final Timestamp configTimestamp,
            final Bytes trustAnchorHash,
            final Bytes destinationVerifier) {
        if (chainId.isEmpty() || trustAnchorHash.length() != WORD) {
            throw new IllegalArgumentException("Channel legs require a nonempty chain ID and a 32-byte anchor hash");
        }
        final var preimage = ByteBuffer.allocate(5 * WORD)
                .put(keccak256DigestOf(chainId.getBytes(UTF_8)))
                .put(keccak256DigestOf(service.toByteArray()))
                .put(encodeTimestamp(configTimestamp))
                .put(trustAnchorHash.toByteArray())
                .put(keccak256DigestOf(destinationVerifier.toByteArray()));
        return Bytes.wrap(keccak256DigestOf(preimage.array()));
    }

    /**
     * Returns the channel ID for two distinct legs, in either order.
     *
     * @param legA one leg hash
     * @param legB the other leg hash
     * @param salt the 32-byte salt
     * @return the 32-byte channel ID
     */
    public static Bytes derive(final Bytes legA, final Bytes legB, final Bytes salt) {
        if (legA.length() != WORD || legB.length() != WORD || salt.length() != WORD) {
            throw new IllegalArgumentException("Channel IDs require 32-byte legs and a 32-byte salt");
        }
        final var a = legA.toByteArray();
        final var b = legB.toByteArray();
        final int order = Arrays.compareUnsigned(a, b);
        if (order == 0) {
            throw new IllegalArgumentException("Channel legs must differ");
        }
        final var preimage = ByteBuffer.allocate(3 * WORD);
        preimage.put(order < 0 ? a : b).put(order < 0 ? b : a).put(salt.toByteArray());
        return Bytes.wrap(keccak256DigestOf(preimage.array()));
    }

    /**
     * Encodes a configuration timestamp as {@code abi.encode(uint96 nanosSinceEpoch)}, the representation
     * verifiers already return from {@code verifyConfig}.
     *
     * @param timestamp a timestamp with non-negative seconds and nanos below one second
     * @return the 32-byte big-endian word
     */
    public static byte[] encodeTimestamp(final Timestamp timestamp) {
        if (timestamp.seconds() < 0 || timestamp.nanos() < 0 || timestamp.nanos() >= 1_000_000_000) {
            throw new IllegalArgumentException("Invalid configuration timestamp " + timestamp);
        }
        final var nanos = BigInteger.valueOf(timestamp.seconds())
                .multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(timestamp.nanos()));
        final var magnitude = nanos.toByteArray();
        final var word = new byte[WORD];
        final int length = Math.min(magnitude.length, WORD);
        System.arraycopy(magnitude, magnitude.length - length, word, WORD - length, length);
        return word;
    }
}
