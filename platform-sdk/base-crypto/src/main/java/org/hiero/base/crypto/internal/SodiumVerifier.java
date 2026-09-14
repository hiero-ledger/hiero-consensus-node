// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.internal;

import com.hedera.cryptography.libsodium.Libsodium;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.foreign.MemorySegment;
import java.security.PublicKey;
import org.hiero.base.crypto.BytesSignatureVerifier;

/**
 * A {@link BytesSignatureVerifier} implementation that uses libsodium to verify signatures using the Ed25519
 * algorithm.
 */
public class SodiumVerifier implements BytesSignatureVerifier {
    private final byte[] publicKey;
    private final MemorySegment publicKeyMemorySegment;

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
        this.publicKeyMemorySegment = MemorySegment.ofArray(this.publicKey);
    }

    @Override
    public boolean verify(@NonNull final Bytes data, @NonNull final Bytes signature) {
        return Libsodium.getInstance()
                        .cryptoSignVerifyDetached(
                                signature.toMemorySegment(),
                                data.toMemorySegment(),
                                Math.toIntExact(data.length()),
                                publicKeyMemorySegment)
                == 0;
    }
}
