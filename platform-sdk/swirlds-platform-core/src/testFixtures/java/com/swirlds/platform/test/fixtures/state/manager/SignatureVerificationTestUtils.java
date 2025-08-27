// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state.manager;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PublicKey;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.base.crypto.SignatureType;

/**
 * Utility methods for testing signature verification.
 */
public class SignatureVerificationTestUtils {

    /**
     * Build a fake signature. The signature acts like a correct signature for the given key/hash, and acts like an
     * invalid signature for any other key/hash.
     */
    public static Signature buildFakeSignature(@NonNull final PublicKey key, @NonNull final Hash hash) {
        return new Signature(SignatureType.RSA, concat(key, hash.getBytes()).toByteArray());
    }

    /**
     * Build a fake signature. The signature acts like a correct signature for the given key/hash, and acts like an
     * invalid signature for any other key/hash.
     */
    public static Bytes buildFakeSignatureBytes(@NonNull final PublicKey key, @NonNull final Hash hash) {
        return concat(key, hash.getBytes());
    }

    /**
     * A {@link com.swirlds.platform.crypto.SignatureVerifier} to be used when using signatures built by {@link #buildFakeSignature(PublicKey, Hash)}
     */
    public static boolean verifySignature(
            @NonNull final Bytes data, @NonNull final Bytes signature, @NonNull final PublicKey publicKey) {
        return concat(publicKey, data).equals(signature);
    }

    private static Bytes concat(@NonNull final PublicKey key, @NonNull final Bytes bytes) {
        final Bytes keyEncoded = Bytes.wrap(key.getEncoded());
        return keyEncoded.append(bytes);
    }
}
