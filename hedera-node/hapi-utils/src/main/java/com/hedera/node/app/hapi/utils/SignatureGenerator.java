// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static org.hiero.base.crypto.SignatureType.ECDSA_SECP256K1;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPrivateKey;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public final class SignatureGenerator {
    private SignatureGenerator() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static final Provider BOUNCYCASTLE_PROVIDER = new BouncyCastleProvider();

    /**
     * Signs a message with a private key.
     *
     * @param msg to be signed
     * @param privateKey private key
     * @return signature in hex format
     * @throws InvalidKeyException if the key is invalid
     * @throws SignatureException if there is an error in the signature
     * @throws NoSuchAlgorithmException if an expected signing algorithm is unavailable
     */
    public static byte[] signBytes(final byte[] msg, final PrivateKey privateKey)
            throws InvalidKeyException, SignatureException, NoSuchAlgorithmException {
        if (privateKey instanceof EdDSAPrivateKey) {
            final var engine = new EdDSAEngine();
            engine.initSign(privateKey);
            return engine.signOneShot(msg);
        } else if (privateKey instanceof ECPrivateKey) {
            final Signature ecdsaSign =
                    Signature.getInstance(ECDSA_SECP256K1.signingAlgorithm(), BOUNCYCASTLE_PROVIDER);
            ecdsaSign.initSign(privateKey);
            ecdsaSign.update(msg);
            final var asn1Sig = ecdsaSign.sign();
            return rawEcdsaSigFromAsn1Der(asn1Sig);
        } else {
            throw new IllegalArgumentException("Unusable private key " + privateKey);
        }
    }

    private static byte[] rawEcdsaSigFromAsn1Der(final byte[] derSig) {
        final var rLenIndex = 3;
        final var rawBytes = new byte[64];

        final var origRLen = derSig[rLenIndex] & 0xff;
        int finalRLen = origRLen;
        int rStart = rLenIndex + 1;
        while (finalRLen > 32) {
            rStart++;
            finalRLen--;
        }
        System.arraycopy(derSig, rStart, rawBytes, 32 - finalRLen, Math.min(32, origRLen));

        final var sLenPos = rLenIndex + (derSig[rLenIndex] & 0xff) + 2;

        final var origSLen = derSig[sLenPos] & 0xff;
        int finalSLen = origSLen;
        int sStart = sLenPos + 1;
        while (finalSLen > 32) {
            sStart++;
            finalSLen--;
        }
        System.arraycopy(derSig, sStart, rawBytes, 32 + (32 - finalSLen), Math.min(32, origSLen));

        return rawBytes;
    }
}
