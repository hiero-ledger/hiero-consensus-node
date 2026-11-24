// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.keccak256DigestOf;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Derives a direction-independent CLPR channel ID from two ledger endpoints and a bytes32 salt. */
public final class ClprChannelId {
    private ClprChannelId() {}

    public static Bytes derive(
            final String localChainId,
            final Bytes localService,
            final Bytes localVerifier,
            final String peerChainId,
            final Bytes peerService,
            final Bytes peerVerifier,
            final Bytes salt) {
        if (localChainId.isEmpty() || peerChainId.isEmpty() || salt.length() != 32) {
            throw new IllegalArgumentException("Channel IDs require nonempty chain IDs and a 32-byte salt");
        }
        final var local = endpointHashFields(localChainId, localService, localVerifier);
        final var peer = endpointHashFields(peerChainId, peerService, peerVerifier);
        final boolean localFirst = Arrays.compareUnsigned(local, peer) <= 0;
        final var preimage = ByteBuffer.allocate(7 * 32);
        preimage.put(localFirst ? local : peer).put(localFirst ? peer : local).put(salt.toByteArray());
        return Bytes.wrap(keccak256DigestOf(preimage.array()));
    }

    private static byte[] endpointHashFields(final String chainId, final Bytes service, final Bytes verifier) {
        return ByteBuffer.allocate(3 * 32)
                .put(keccak256DigestOf(chainId.getBytes(UTF_8)))
                .put(keccak256DigestOf(service.toByteArray()))
                .put(keccak256DigestOf(verifier.toByteArray()))
                .array();
    }
}
