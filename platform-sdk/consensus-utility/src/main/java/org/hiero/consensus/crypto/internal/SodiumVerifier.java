// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto.internal;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PublicKey;
import org.hiero.base.crypto.BytesSignatureVerifier;

/**
 * A {@link BytesSignatureVerifier} implementation that uses libsodium to verify signatures using the Ed25519
 * algorithm.
 */
public class SodiumVerifier implements BytesSignatureVerifier {
    private final byte[] publicKey;

    /**
     * Constructs a SodiumVerifier with the given Ed25519 PublicKey.
     *
     * @param publicKey the Ed25519 PublicKey to use for signature verification
     */
    public SodiumVerifier(@NonNull final PublicKey publicKey) {
        final byte[] encoded = publicKey.getEncoded();
        this.publicKey = new byte[32];
        // Extract 32-byte raw public key from X.509 encoded public key
        System.arraycopy(encoded, encoded.length - 32, this.publicKey, 0, 32);
    }

    @Override
    public boolean verify(@NonNull final Bytes data, @NonNull final Bytes signature) {
        return SodiumJni.SODIUM.cryptoSignVerifyDetached(
                signature.toByteArray(), data.toByteArray(), Math.toIntExact(data.length()), publicKey);
    }
}
