// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Enumeration of supported signing schemas.
 */
public enum SigningSchema {
    /**
     * RSA signing schema, the only one used by the original implementation. This currently refernces constants defined
     * in CryptoConstants since these constants are used in multiple places in the codebase. Once the system is fully
     * migrated to use this enum, these constants can be removed.
     */
    RSA(CryptoConstants.SIG_TYPE1, CryptoConstants.SIG_KEY_SIZE_BITS, CryptoConstants.SIG_TYPE2),
    /**
     * Elliptic Curve signing schema.
     */
    EC("EC", 384, "SHA384withECDSA"),
    /**
     * Ed25519 signing schema.
     */
    ED25519("Ed25519", 255, "Ed25519");

    private final String keyType;
    private final String signingAlgorithm;
    private final int keySizeBits;

    /**
     * Constructor.
     *
     * @param keyType          the key type used, defined by the Java Security Standard Algorithm Names
     * @param keySizeBits      the key size in bits
     * @param signingAlgorithm the signing algorithm used, defined by the Java Security Standard Algorithm Names
     */
    SigningSchema(@NonNull final String keyType, final int keySizeBits, @NonNull final String signingAlgorithm) {
        this.keyType = keyType;
        this.signingAlgorithm = signingAlgorithm;
        this.keySizeBits = keySizeBits;
    }

    /**
     * Get the key type.
     *
     * @return the key type
     */
    public @NonNull String getKeyType() {
        return keyType;
    }

    /**
     * Get the signing algorithm.
     *
     * @return the signing algorithm
     */
    public @NonNull String getSigningAlgorithm() {
        return signingAlgorithm;
    }

    /**
     * Get the key size in bits.
     *
     * @return the key size in bits
     */
    public int getKeySizeBits() {
        return keySizeBits;
    }
}
