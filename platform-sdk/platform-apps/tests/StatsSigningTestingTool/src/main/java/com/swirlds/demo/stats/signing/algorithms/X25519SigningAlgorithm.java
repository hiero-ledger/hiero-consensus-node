// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats.signing.algorithms;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static org.hiero.consensus.model.utility.CommonUtils.hex;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;
import com.swirlds.common.crypto.SignatureType;
import java.security.SignatureException;
import java.util.Arrays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Implements the {@code ED22519} elliptical curve digital signature algorithm.
 */
public class X25519SigningAlgorithm implements SigningAlgorithm {

    /**
     * the unique algorithm identifier
     */
    private static final byte ALGORITHM_ID = 1;

    /**
     * use this for all logging, as controlled by the optional data/log4j2.xml file
     */
    private static final Logger logger = LogManager.getLogger(X25519SigningAlgorithm.class);

    /**
     * the length of signature in bytes
     */
    private static final int SIGNATURE_LENGTH = Sign.BYTES;

    /**
     * the length of the public key in bytes
     */
    private static final int PUBLIC_KEY_LENGTH = Sign.PUBLICKEYBYTES;

    /**
     * the length of the private key in bytes
     */
    private static final int PRIVATE_KEY_LENGTH = Sign.SECRETKEYBYTES;

    /**
     * the elliptical curve coordinate size in bytes
     */
    private static final int ED_COORD_SIZE = 32;

    /**
     * the native NaCl signing interface
     */
    private Sign.Native signer;

    /**
     * the precomputed public key
     */
    private byte[] publicKey;

    /**
     * the precomputed private key
     */
    private byte[] privateKey;

    /**
     * indicates if the algorithm is available and initialized
     */
    private boolean algorithmAvailable;

    /**
     * {@inheritDoc}
     */
    @Override
    public byte getId() {
        return ALGORITHM_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getPublicKey() {
        return publicKey;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getPrivateKey() {
        return privateKey;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] sign(final byte[] buffer, final int offset, final int len) throws SignatureException {
        final byte[] sig = new byte[SIGNATURE_LENGTH];

        final byte[] data;

        if (offset != 0 || len != buffer.length) {
            final int newSize = buffer.length - offset;
            data = new byte[newSize];
            System.arraycopy(buffer, offset, data, 0, data.length);
        } else {
            data = buffer;
        }

        if (!signer.cryptoSignDetached(sig, data, data.length, privateKey)) {
            throw new SignatureException();
        }

        return sig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExtendedSignature signEx(final byte[] buffer, final int offset, final int len) throws SignatureException {
        final byte[] sig = sign(buffer, offset, len);
        final byte[] r = Arrays.copyOfRange(sig, 0, ED_COORD_SIZE);
        final byte[] s = Arrays.copyOfRange(sig, ED_COORD_SIZE, sig.length);

        return new ExtendedSignature(sig, r, s);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] hash(byte[] buffer, int offset, int len) {
        throw new UnsupportedOperationException("External hashing is not supported by this algorithm");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tryAcquirePrimitives() {
        try {
            final SodiumJava sodium = new SodiumJava();
            signer = new LazySodiumJava(sodium);

            publicKey = new byte[PUBLIC_KEY_LENGTH];
            privateKey = new byte[PRIVATE_KEY_LENGTH];

            algorithmAvailable = signer.cryptoSignKeypair(publicKey, privateKey);
            logger.trace(
                    STARTUP.getMarker(),
                    "Public Key Tracing [ signatureType = {}, key = {} ]",
                    getSignatureType(),
                    hex(publicKey));
        } catch (Exception e) {
            logger.warn(
                    EXCEPTION.getMarker(),
                    "Algorithm Disabled: Exception During Initialization [ id = {}, class = {} ]",
                    getId(),
                    this.getClass().getName(),
                    e);
            algorithmAvailable = false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAvailable() {
        return algorithmAvailable;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getSignatureLength() {
        return SIGNATURE_LENGTH;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getPublicKeyLength() {
        return PUBLIC_KEY_LENGTH;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getCoordinateSize() {
        return ED_COORD_SIZE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SignatureType getSignatureType() {
        return SignatureType.ED25519;
    }
}
