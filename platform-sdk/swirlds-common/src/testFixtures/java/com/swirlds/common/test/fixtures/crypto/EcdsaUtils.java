// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.crypto;

import static com.swirlds.common.crypto.SignatureType.ECDSA_SECP256K1;
import static org.hiero.consensus.model.utility.CommonUtils.unhex;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

/**
 * Utility class for ECDSA keys
 */
public final class EcdsaUtils {
    public static final int SIGNATURE_LENGTH = 64;
    public static final int BIG_ENDIAN_SIZE = 32;
    /* A digest value re-used in several tests. */
    public static final byte[] WELL_KNOWN_DIGEST =
            unhex("744c77a7af70b3a522009f0a963384eccfa77662a594d6e0247dfba095eb48d5");

    private EcdsaUtils() {}

    /**
     * Returns a randomly generated ECDSA(secp256k1) key pair.
     *
     * @return a random key pair
     */
    public static KeyPair genEcdsaSecp256k1KeyPair()
            throws NoSuchAlgorithmException, InvalidAlgorithmParameterException, NoSuchProviderException {
        final ECGenParameterSpec ecSpec = new ECGenParameterSpec(ECDSA_SECP256K1.ellipticalCurve());
        final KeyPairGenerator generator =
                KeyPairGenerator.getInstance(ECDSA_SECP256K1.keyAlgorithm(), ECDSA_SECP256K1.provider());
        generator.initialize(ecSpec, new SecureRandom());
        return generator.generateKeyPair();
    }

    /**
     * Signs the well-known digest with the given ECDSA(secp256k1) private key and returns the
     * raw signature; that is, the 64-byte concatenation of the signature's {@code r} value
     * followed by its {@code s} value as unsigned big-endians.
     *
     * @param privateKey
     * 		the private key to use
     * @return the raw signature of the well-known digest
     */
    public static byte[] signWellKnownDigestWithEcdsaSecp256k1(final PrivateKey privateKey) {
        return signDigestWithEcdsaSecp256k1(privateKey, WELL_KNOWN_DIGEST);
    }

    /**
     * Signs the given digest with the given ECDSA(secp256k1) private key and returns the
     * raw signature; that is, the 64-byte concatenation of the signature's {@code r} value
     * followed by its {@code s} value in unsigned big-endian form.
     *
     * @param privateKey
     * 		the private key to use
     * @return the raw signature of the given digest
     */
    public static byte[] signDigestWithEcdsaSecp256k1(final PrivateKey privateKey, final byte[] digest) {
        try {
            final Signature ecdsaSign =
                    Signature.getInstance(ECDSA_SECP256K1.signingAlgorithm(), ECDSA_SECP256K1.provider());
            ecdsaSign.initSign(privateKey);
            ecdsaSign.update(digest);
            final byte[] asn1Sig = ecdsaSign.sign();
            return rawEcdsaSigFromAsn1Der(asn1Sig);
        } catch (final NoSuchAlgorithmException
                | InvalidKeyException
                | SignatureException
                | NoSuchProviderException fatal) {
            throw new IllegalStateException(fatal);
        }
    }

    /**
     * Returns the 64-byte raw form of a given ECDSA(secp256k1) public key; that is, the 64-byte
     * concatenation of the public key's x- and y-coordinates in unsigned big-endian form.
     *
     * @param cryptoPubKey
     * 		the public key to encode
     * @return the raw coordinates of the public key
     */
    public static byte[] asRawEcdsaSecp256k1Key(final ECPublicKey cryptoPubKey) {
        final byte[] encPubKey = cryptoPubKey.getEncoded();
        return Arrays.copyOfRange(encPubKey, encPubKey.length - SIGNATURE_LENGTH, encPubKey.length);
    }

    /**
     * Compresses an ASN.1 DER-encoded ECDSA signature into the concatenation of the signature's
     * {@code r} and {@code s} points encoded as unsigned big-endian 32-byte integers.
     *
     * @param derSig
     * 		the ASN.1 DER-encoded signature
     * @return the raw (r, s) points
     */
    private static byte[] rawEcdsaSigFromAsn1Der(final byte[] derSig) {
        final int R_LEN_INDEX = 3;
        final byte[] rawBytes = new byte[SIGNATURE_LENGTH];

        final int origRLen = derSig[R_LEN_INDEX] & 0xff;
        int finalRLen = origRLen;
        int rStart = R_LEN_INDEX + 1;
        while (finalRLen > BIG_ENDIAN_SIZE) {
            rStart++;
            finalRLen--;
        }
        System.arraycopy(derSig, rStart, rawBytes, BIG_ENDIAN_SIZE - finalRLen, Math.min(BIG_ENDIAN_SIZE, origRLen));

        final int sLenPos = R_LEN_INDEX + (derSig[R_LEN_INDEX] & 0xff) + 2;

        final int origSLen = derSig[sLenPos] & 0xff;
        int finalSLen = origSLen;
        int sStart = sLenPos + 1;
        while (finalSLen > BIG_ENDIAN_SIZE) {
            sStart++;
            finalSLen--;
        }
        System.arraycopy(
                derSig,
                sStart,
                rawBytes,
                BIG_ENDIAN_SIZE + (BIG_ENDIAN_SIZE - finalSLen),
                Math.min(BIG_ENDIAN_SIZE, origSLen));

        return rawBytes;
    }
}
