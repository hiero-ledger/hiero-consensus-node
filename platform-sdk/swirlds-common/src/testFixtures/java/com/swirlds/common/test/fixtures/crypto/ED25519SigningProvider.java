// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.crypto;

import static org.hiero.consensus.model.utility.CommonUtils.hex;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;
import java.security.SignatureException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

public class ED25519SigningProvider implements SigningProvider {
    /**
     * the length of signature in bytes
     */
    public static final int SIGNATURE_LENGTH = Sign.BYTES;

    /**
     * the length of the public key in bytes
     */
    public static final int PUBLIC_KEY_LENGTH = Sign.PUBLICKEYBYTES;

    /**
     * the length of the private key in bytes
     */
    public static final int PRIVATE_KEY_LENGTH = Sign.SECRETKEYBYTES;

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
    private Sign.Native signer;

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
        final SodiumJava sodium = new SodiumJava();
        signer = new LazySodiumJava(sodium);

        publicKey = new byte[PUBLIC_KEY_LENGTH];
        privateKey = new byte[PRIVATE_KEY_LENGTH];

        algorithmAvailable = signer.cryptoSignKeypair(publicKey, privateKey);
        logger.trace(LOGM_STARTUP, "Public Key -> hex('{}')", () -> hex(publicKey));
        algorithmAvailable = true;
    }
}
