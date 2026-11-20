// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.test.fixtures;

import static org.hiero.base.crypto.ECDSASecp256k1.signDigestWithEcdsaSecp256k1;
import static org.hiero.base.crypto.test.fixtures.EcdsaUtils.asRawEcdsaSecp256k1Key;

import java.lang.foreign.MemorySegment;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import org.hiero.base.crypto.ECDSASecp256k1;
import org.hiero.base.crypto.Keccak256;

public class ECDSASigningProvider implements SigningProvider {
    /* Used to share a generated keypair between instance methods */
    private KeyPair activeKp;
    /**
     * indicates whether there is an available algorithm implementation & keypair
     */
    private boolean algorithmAvailable = false;

    public ECDSASigningProvider() {
        generateActiveKeyPair();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] sign(byte[] msg) throws SignatureException {
        return signDigestWithEcdsaSecp256k1(
                activeKp.getPrivate(), Keccak256.keccak256DigestOf(MemorySegment.ofArray(msg)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getPublicKeyBytes() {
        return asRawEcdsaSecp256k1Key((ECPublicKey) activeKp.getPublic());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getSignatureLength() {
        return EcdsaUtils.SIGNATURE_LENGTH;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getPrivateKeyBytes() {
        return activeKp.getPublic().getEncoded();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAlgorithmAvailable() {
        return algorithmAvailable;
    }

    /**
     * Generate an ECDSASecp256K1 keypair
     */
    void generateActiveKeyPair() {
        activeKp = ECDSASecp256k1.genEcdsaSecp256k1KeyPair();
        algorithmAvailable = true;
    }
}
