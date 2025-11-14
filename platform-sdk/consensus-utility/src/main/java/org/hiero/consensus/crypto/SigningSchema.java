// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.Key;

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

    public static SigningSchema fromKeyType(@NonNull final Key key) {
        final String algorithm = key.getAlgorithm();
        return switch (algorithm) {
            case "RSA" -> RSA;
            // An EdDSA can be either Ed25519 or Ed448, but there is no simple way to distinguish them from the Key
            // interface.
            case "Ed25519", "EdDSA" -> ED25519;
            default -> throw new IllegalArgumentException("Unsupported key algorithm: " + algorithm);
        };
    }
}
