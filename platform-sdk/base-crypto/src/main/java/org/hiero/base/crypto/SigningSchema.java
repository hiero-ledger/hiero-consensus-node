// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.Key;
import java.security.interfaces.EdECKey;
import java.security.spec.NamedParameterSpec;

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

    /**
     * Get the signing schema of the given key.
     *
     * @param key the public or private key
     * @return the signing schema of the key
     * @throws IllegalArgumentException if the key does not belong to a supported signing schema
     */
    @NonNull
    public static SigningSchema fromKeyType(@NonNull final Key key) {
        final SigningSchema schema = schemaOf(key);
        if (schema == null) {
            throw new IllegalArgumentException("Unsupported key: " + describe(key));
        }
        return schema;
    }

    /**
     * Check whether the given key belongs to a supported signing schema.
     *
     * @param key the public or private key
     * @return {@code true} if {@link #fromKeyType(Key)} accepts the key, otherwise {@code false}
     */
    public static boolean isSupported(@NonNull final Key key) {
        return schemaOf(key) != null;
    }

    @Nullable
    private static SigningSchema schemaOf(@NonNull final Key key) {
        return switch (key.getAlgorithm()) {
            case "RSA" -> RSA;
            case "Ed25519" -> ED25519;
            // The JDK reports both Ed25519 and Ed448 keys as "EdDSA", so the curve is taken from the key parameters.
            case "EdDSA" ->
                key instanceof final EdECKey edKey
                                && NamedParameterSpec.ED25519
                                        .getName()
                                        .equals(edKey.getParams().getName())
                        ? ED25519
                        : null;
            default -> null;
        };
    }

    @NonNull
    private static String describe(@NonNull final Key key) {
        return key instanceof final EdECKey edKey
                ? key.getAlgorithm() + " (" + edKey.getParams().getName() + ")"
                : key.getAlgorithm();
    }
}
