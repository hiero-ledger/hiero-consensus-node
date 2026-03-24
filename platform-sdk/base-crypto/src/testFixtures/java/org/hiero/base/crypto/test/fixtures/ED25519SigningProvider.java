// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.test.fixtures;

import static org.hiero.base.utility.CommonUtils.hex;

import java.security.SignatureException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.base.crypto.engine.LibSodiumEd25519;

public class ED25519SigningProvider implements SigningProvider {
    /**
     * the length of signature in bytes
     */
    public static final int SIGNATURE_LENGTH = LibSodiumEd25519.SIGNATURE_BYTES;

    /**
     * the length of the public key in bytes
     */
    public static final int PUBLIC_KEY_LENGTH = LibSodiumEd25519.PUBLIC_KEY_BYTES;

    /**
     * the length of the private key in bytes
     */
    public static final int PRIVATE_KEY_LENGTH = LibSodiumEd25519.SECRET_KEY_BYTES;

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

    /**
     * the public key for each signed transaction
     */
    private byte[] publicKey;

    /**
     * the native NaCl signing interface
     */
    private LibSodiumEd25519 signer;

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
        if (!signer.cryptoSignDetached(sig, data, data.length, privateKey)) {
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
        signer = LibSodiumEd25519.INSTANCE;

        publicKey = new byte[PUBLIC_KEY_LENGTH];
        privateKey = new byte[PRIVATE_KEY_LENGTH];

        algorithmAvailable = signer.cryptoSignKeypair(publicKey, privateKey);
        logger.trace(LOGM_STARTUP, "Public Key -> hex('{}')", () -> hex(publicKey));
        algorithmAvailable = true;
    }
}
