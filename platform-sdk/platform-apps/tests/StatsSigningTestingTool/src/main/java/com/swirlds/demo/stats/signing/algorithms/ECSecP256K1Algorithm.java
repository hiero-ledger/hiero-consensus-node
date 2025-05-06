// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats.signing.algorithms;

import org.hiero.base.crypto.SignatureType;

/**
 * Implements the {@code ECDSA} digital signature
 */
public class ECSecP256K1Algorithm extends ECSigningAlgorithm {

    /**
     * the unique algorithm identifier
     */
    private static final byte ALGORITHM_ID = 2;

    /**
     * the signature length in bytes
     */
    private static final int SIGNATURE_LENGTH = 64;

    /**
     * the public key length in bytes
     */
    private static final int PUBLIC_KEY_LENGTH = 64;

    /**
     * the elliptical curve coordinate size in bytes
     */
    private static final int EC_COORD_SIZE = 32;

    public ECSecP256K1Algorithm() {
        super(SignatureType.ECDSA_SECP256K1);
    }

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
        return EC_COORD_SIZE;
    }
}
