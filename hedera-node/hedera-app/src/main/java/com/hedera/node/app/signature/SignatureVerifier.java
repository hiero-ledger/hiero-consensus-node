// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature;

import static com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType.RAW;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * Asynchronously verifies signatures.
 */
public interface SignatureVerifier {
    /**
     * Asynchronously verifies that the given {@code sigPairs} match the given {@code signedBytes} on a
     * payload of raw bytes that must be hashed via Keccak-256 before verifying ECDSA(secp256k1) signatures.
     *
     * @param signedBytes The signed bytes to verify
     * @param sigPairs The matching set of signatures to be verified
     * @return A {@link Set} of {@link Future}s, one per {@link ExpandedSignaturePair}.
     */
    @NonNull
    default Map<Key, SignatureVerificationFuture> verify(
            @NonNull final Bytes signedBytes, @NonNull final Set<ExpandedSignaturePair> sigPairs) {
        return verify(signedBytes, sigPairs, RAW);
    }

    /**
     * Asynchronously verifies that the given {@code sigPairs} match the given {@code signedBytes}.
     *
     * @param signedBytes The signed bytes to verify
     * @param sigPairs The matching set of signatures to be verified
     * @param messageType The type of message being verified, either a raw payload or a Keccak-256 hash
     * @return A {@link Set} of {@link Future}s, one per {@link ExpandedSignaturePair}.
     */
    @NonNull
    Map<Key, SignatureVerificationFuture> verify(
            @NonNull Bytes signedBytes, @NonNull Set<ExpandedSignaturePair> sigPairs, @NonNull MessageType messageType);
}
