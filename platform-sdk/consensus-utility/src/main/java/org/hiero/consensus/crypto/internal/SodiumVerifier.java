// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto.internal;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PublicKey;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.BytesSignatureVerifier;

/**
 * A {@link BytesSignatureVerifier} implementation that uses libsodium to verify signatures using the Ed25519
 * algorithm.
 */
public class SodiumVerifier implements BytesSignatureVerifier {
    private static final Logger logger = LogManager.getLogger(SodiumVerifier.class);
    private static final AtomicBoolean logged = new AtomicBoolean(false);

    private final byte[] publicKey;

    /**
     * Constructs a SodiumVerifier with the given Ed25519 PublicKey.
     *
     * @param publicKey the Ed25519 PublicKey to use for signature verification
     */
    public SodiumVerifier(@NonNull final PublicKey publicKey) {
        if (logged.compareAndSet(false, true)) {
            logger.info("SodiumVerifier: algorithm=Ed25519 provider=native libsodium (non-JCA)");
        }
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
