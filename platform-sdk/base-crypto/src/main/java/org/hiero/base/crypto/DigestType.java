// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public enum DigestType {
    /** 384-bit SHA2 message digest meeting current CNSA standards */
    SHA_384(0x58ff811b, "SHA-384", "SUN", 48),

    /** 512-bit SHA2 message digest meeting current CNSA standards */
    SHA_512(0x8fc9497e, "SHA-512", "SUN", 64);

    /**
     * Enum constructor used to initialize the values with the algorithm characteristics.
     *
     * @param id            a unique integer identifier for this algorithm
     * @param algorithmName the JCE algorithm name
     * @param provider      the JCE provider name
     * @param outputLength  output length in bytes
     */
    DigestType(final int id, final String algorithmName, final String provider, final int outputLength) {
        this.id = id;
        this.algorithmName = algorithmName;
        this.provider = provider;
        this.outputLength = outputLength;
    }

    /**
     * the max length of digest output in bytes among all DigestType
     */
    private static final int MAX_LENGTH = 64;

    /**
     * The unique identifier for this algorithm. Used when serializing this enumerations values.
     */
    private final int id;

    /**
     * the JCE name for the algorithm
     */
    private final String algorithmName;

    /**
     * the JCE name for the cryptography provider
     */
    private final String provider;

    /**
     * the length of the digest output in bytes
     */
    private final int outputLength;

    /**
     * @param id the unique identifier
     * @return a valid DigestType or null if the provided id is not valid
     */
    public static DigestType valueOf(final int id) {
        switch (id) {
            case 0x58ff811b:
                return SHA_384;
            case 0x8fc9497e:
                return SHA_512;
            default:
                return null;
        }
    }

    /**
     * Getter to retrieve the unique identifier for the algorithm.
     *
     * @return the unique identifier
     */
    public int id() {
        return id;
    }

    /**
     * Getter to retrieve the JCE name for the algorithm.
     *
     * @return the JCE algorithm name
     */
    public String algorithmName() {
        return algorithmName;
    }

    /**
     * Getter to retrieve the JCE name for the cryptography provider.
     *
     * @return the JCE provider name
     */
    public String provider() {
        return provider;
    }

    /**
     * Getter to retrieve the length of digest output in bytes.
     *
     * @return the length of the digest output in bytes
     */
    public int digestLength() {
        return outputLength;
    }

    /**
     * Getter to retrieve the max length of digest output in bytes among all DigestType
     *
     * @return the max length of digest output in bytes among all DigestType
     */
    public static int getMaxLength() {
        return MAX_LENGTH;
    }

    /**
     * Build a digest instance for this algorithm.
     *
     * @return a new message digest
     */
    @NonNull
    public MessageDigest buildDigest() {
        try {
            return MessageDigest.getInstance(algorithmName());
        } catch (final NoSuchAlgorithmException e) {
            throw new RuntimeException("unable to create digest type", e);
        }
    }
}
