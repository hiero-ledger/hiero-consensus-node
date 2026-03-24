// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Interface for verifying signatures. Intended to be used in conjunction with {@link BytesSigner}.
 */
public interface BytesSignatureVerifier {
    /**
     * Verify the supplied signature for the given data
     *
     * @param data      the data that was signed
     * @param signature the signature to verify
     * @return true if the signature is valid for the data, false otherwise
     */
    boolean verify(@NonNull Bytes data, @NonNull Bytes signature);

    /**
     * Verify a signature using pre-extracted byte arrays, avoiding allocation of intermediate
     * {@link Bytes} objects. Callers can reuse buffers across invocations to eliminate per-call
     * heap allocations and reduce GC pressure.
     *
     * <p>The default implementation wraps the arrays in {@link Bytes} and delegates to
     * {@link #verify(Bytes, Bytes)}. Implementations that can consume raw byte arrays directly
     * (e.g. libsodium via JNI) should override this for optimal performance.
     *
     * @param data         the buffer containing the data that was signed
     * @param dataLen      the number of valid bytes in {@code data}
     * @param signature    the buffer containing the signature to verify
     * @param signatureLen the number of valid bytes in {@code signature}
     * @return true if the signature is valid for the data, false otherwise
     */
    default boolean verifyRaw(@NonNull byte[] data, int dataLen, @NonNull byte[] signature, int signatureLen) {
        return verify(Bytes.wrap(data, 0, dataLen), Bytes.wrap(signature, 0, signatureLen));
    }

    /**
     * Whether this verifier instance is safe for concurrent use by multiple threads.
     * Implementations that hold mutable state (e.g. {@link java.security.Signature}) should
     * return {@code false}. Stateless or immutable implementations should return {@code true}.
     *
     * @return {@code true} if this verifier can be shared across threads, {@code false} otherwise
     */
    default boolean isThreadSafe() {
        return false;
    }
}
