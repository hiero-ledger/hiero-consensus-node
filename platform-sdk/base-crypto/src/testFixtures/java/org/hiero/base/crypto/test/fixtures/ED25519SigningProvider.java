// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.test.fixtures;

import static org.hiero.base.utility.CommonUtils.hex;

import com.hedera.cryptography.libsodium.Libsodium;
import java.lang.foreign.MemorySegment;
import java.security.SignatureException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

public class ED25519SigningProvider implements SigningProvider {
    /**
     * the length of signature in bytes
     */
    public static final int SIGNATURE_LENGTH = Libsodium.ED25519_BYTES;

    /**
     * the length of the public key in bytes
     */
    public static final int PUBLIC_KEY_LENGTH = Libsodium.ED25519_PUBLICKEYBYTES;

    /**
     * the length of the private key in bytes
     */
    public static final int PRIVATE_KEY_LENGTH = Libsodium.ED25519_SECRETKEYBYTES;

    /**
     * use this for all logging, as controlled by the optional data/log4j2.xml file
     */
    private static final Logger logger = LogManager.getLogger(ED25519SigningProvider.class);

    /**
     * logs events related to the startup of the application
     */
    private static final Marker LOGM_STARTUP = MarkerManager.getMarker("STARTUP");

    /**
     * the private key to use when signing each transaction
     */
    private byte[] privateKey;

    /// Cached MemorySegment for the privateKey.
    private MemorySegment privateKeyMemorySegment;

    /**
     * the public key for each signed transaction
     */
    private byte[] publicKey;

    /// Libsodium signer that calls the native library.
    private Libsodium signer;

    /**
     * indicates whether there is an available algorithm implementation & keypair
     */
    private boolean algorithmAvailable = false;

    public ED25519SigningProvider() {
        tryAcquireSignature();
    }

    @Override
    public byte[] sign(byte[] data) throws SignatureException {
        final byte[] sig = new byte[SIGNATURE_LENGTH];
        if (signer.cryptoSignDetached(
                        MemorySegment.ofArray(sig),
                        MemorySegment.NULL,
                        MemorySegment.ofArray(data),
                        data.length,
                        privateKeyMemorySegment)
                != 0) {
            throw new SignatureException();
        }

        return sig;
    }

    @Override
    public byte[] getPublicKeyBytes() {
        return publicKey;
    }

    @Override
    public int getSignatureLength() {
        return SIGNATURE_LENGTH;
    }

    @Override
    public byte[] getPrivateKeyBytes() {
        return privateKey;
    }

    @Override
    public boolean isAlgorithmAvailable() {
        return algorithmAvailable;
    }

    /**
     * Initializes the {@link #signer} instance and creates the public/private keys.
     */
    private void tryAcquireSignature() {
        signer = Libsodium.getInstance();

        publicKey = new byte[PUBLIC_KEY_LENGTH];
        privateKey = new byte[PRIVATE_KEY_LENGTH];

        algorithmAvailable =
                signer.cryptoSignKeypair(MemorySegment.ofArray(publicKey), MemorySegment.ofArray(privateKey)) == 0;
        logger.trace(LOGM_STARTUP, "Public Key -> hex('{}')", () -> hex(publicKey));
        algorithmAvailable = true;
        privateKeyMemorySegment = MemorySegment.ofArray(privateKey);
    }
}
