// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.security.interfaces.ECPrivateKey;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import org.hiero.base.crypto.ECDSASecp256k1;

public final class SignatureGenerator {
    private SignatureGenerator() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Signs a message with a private key.
     *
     * @param msg to be signed. ECDSA signing requires the msg to be a 32-byte digest (e.g. Keccak256.)
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
            return ECDSASecp256k1.signDigestWithEcdsaSecp256k1(privateKey, msg);
        } else {
            throw new IllegalArgumentException("Unusable private key " + privateKey);
        }
    }
}
