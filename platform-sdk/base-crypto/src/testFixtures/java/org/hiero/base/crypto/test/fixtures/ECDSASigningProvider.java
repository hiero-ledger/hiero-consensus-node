// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.test.fixtures;

import static org.hiero.base.crypto.test.fixtures.EcdsaUtils.asRawEcdsaSecp256k1Key;
import static org.hiero.base.crypto.test.fixtures.EcdsaUtils.signDigestWithEcdsaSecp256k1;

import java.security.*;
import java.security.interfaces.ECPublicKey;

public class ECDSASigningProvider implements SigningProvider {
    /* Used to share a generated keypair between instance methods */
    private KeyPair activeKp;
    /**
     * indicates whether there is an available algorithm implementation & keypair
     */
    private boolean algorithmAvailable = false;

    /**
     * An instance of the Keccak digest algorithm used to hash the message to be signed.
     */
    private final MessageDigest keccakDigest;

    public ECDSASigningProvider() {
        generateActiveKeyPair();
        this.keccakDigest = createKeccakDigest();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] sign(byte[] msg) throws SignatureException {
        return signDigestWithEcdsaSecp256k1(activeKp.getPrivate(), keccak256(msg));
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
        try {
            activeKp = EcdsaUtils.genEcdsaSecp256k1KeyPair();
            algorithmAvailable = true;
        } catch (final NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchProviderException fatal) {
            throw new IllegalStateException("Tests cannot be trusted without working key-pair generation", fatal);
        }
    }

    private MessageDigest createKeccakDigest() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("KECCAK-256");
        } catch (NoSuchAlgorithmException ignored) {
            try {
                digest = MessageDigest.getInstance("SHA3-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        return digest;
    }

    private byte[] keccak256(final byte[] bytes) {
        keccakDigest.reset();
        keccakDigest.update(bytes);
        return keccakDigest.digest();
    }
}
