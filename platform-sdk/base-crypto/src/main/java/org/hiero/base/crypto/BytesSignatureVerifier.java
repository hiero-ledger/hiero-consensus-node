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
