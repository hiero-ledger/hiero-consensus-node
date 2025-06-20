// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * The type of cryptographic algorithm used to create a signature.
 */
public enum SignatureType {
    /** An Ed25519 signature which uses a SHA-512 hash and a 32 byte public key */
    ED25519(0x0, "NONEwithED25519", "ED25519", "", 64, "x25519"),

    /** An RSA signature as specified by the FIPS 186-4 standards */
    RSA(0x1, "SHA384withRSA", "RSA", getBCProviderName(), 384),

    /** An Elliptical Curve based signature using the secp256k1 curve (which is not FIPS 186-4 compliant) */
    ECDSA_SECP256K1(0x2, "NONEwithECDSA", "EC", getBCProviderName(), 64, "secp256k1");

    /* Ensure BouncyCastle provider is added before the name is used */
    private static String getBCProviderName() {
        Security.addProvider(new BouncyCastleProvider());
        return BouncyCastleProvider.PROVIDER_NAME;
    }

    /**
     * The unique identifier used for serialization and deserialization purposes.
     */
    private final int id;
    /**
     * The JCE name for the signing algorithm.
     */
    private final String signingAlgorithm;
    /**
     * The JCE name for the key generation algorithm.
     */
    private final String keyAlgorithm;
    /**
     * The JCE name for the cryptography provider.
     */
    private final String provider;
    /**
     * The length of the signature in bytes.
     */
    private final int signatureLength;
    /**
     * The elliptical curve to be used. Only applies to EC algorithms and may be null or an empty string for all others.
     */
    private final String ellipticalCurve;

    /**
     * Enum constructor used to initialize the values with the algorithm characteristics.
     *
     * @param id
     * 		the unique identifier for this algorithm
     * @param signingAlgorithm
     * 		the JCE signing algorithm name
     * @param keyAlgorithm
     * 		the JCE key generation algorithm name
     * @param provider
     * 		the JCE provider name
     * @param signatureLength
     * 		The length of the signature in bytes
     */
    SignatureType(
            final int id,
            final String signingAlgorithm,
            final String keyAlgorithm,
            final String provider,
            final int signatureLength) {
        this(id, signingAlgorithm, keyAlgorithm, provider, signatureLength, null);
    }

    /**
     * Enum constructor used to initialize the values with the algorithm characteristics.
     *
     * @param id
     * 		the unique identifier for this algorithm
     * @param signingAlgorithm
     * 		the JCE signing algorithm name
     * @param keyAlgorithm
     * 		the JCE key generation algorithm name
     * @param provider
     * 		the JCE provider name
     * @param signatureLength
     * 		The length of the signature in bytes
     */
    SignatureType(
            final int id,
            final String signingAlgorithm,
            final String keyAlgorithm,
            final String provider,
            final int signatureLength,
            final String ellipticalCurve) {
        this.id = id;
        this.signingAlgorithm = signingAlgorithm;
        this.keyAlgorithm = keyAlgorithm;
        this.provider = provider;
        this.signatureLength = signatureLength;
        this.ellipticalCurve = ellipticalCurve;
    }

    /**
     * Translates an ordinal position into an enumeration value.
     *
     * @param id
     * 		the unique identifier to be translated into an enumeration value.
     * @param defaultValue
     * 		the default enumeration value to return if the {@code ordinal} cannot be found.
     * @return the enumeration value related to the given ordinal or the default value if the ordinal is not found.
     */
    public static SignatureType from(final int id, final SignatureType defaultValue) {
        return switch (id) {
            case 0x0 -> ED25519;
            case 0x1 -> RSA;
            case 0x2 -> ECDSA_SECP256K1;
            default -> defaultValue;
        };
    }

    /**
     * Getter to retrieve the unique identifier for the signing algorithm.
     *
     * @return the unique identifier
     */
    public int id() {
        return this.id;
    }

    /**
     * Getter to retrieve the JCE name for the signing algorithm.
     *
     * @return the JCE signing algorithm name
     */
    public String signingAlgorithm() {
        return this.signingAlgorithm;
    }

    /**
     * Getter to retrieve the JCE name for the key generation algorithm.
     *
     * @return the JCE key generation algorithm name
     */
    public String keyAlgorithm() {
        return this.keyAlgorithm;
    }

    /**
     * Getter to retrieve the JCE name for the cryptography provider.
     *
     * @return the JCE provider name
     */
    public String provider() {
        return this.provider;
    }

    /**
     * Getter to retrieve the length of the signature in bytes
     *
     * @return the length of the signature
     */
    public int signatureLength() {
        return signatureLength;
    }

    /**
     * Getter to retrieve the elliptical curve to be used in the transformation.
     *
     * @return the elliptical curve spec name as required by the {@link java.security.spec.ECParameterSpec}
     * 		implementation.
     */
    public String ellipticalCurve() {
        return ellipticalCurve;
    }
}
