// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static org.hiero.consensus.crypto.SigningSchema.RSA;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An enumeration of the different implementations of {@link SigningSchema} supported.
 */
public enum SigningImplementation {
    /**
     * RSA implementation using Bouncy Castle as the security provider.
     */
    RSA_BC(RSA, CryptoConstants.SIG_PROVIDER),
    /**
     * RSA implementation using the default JDK security provider.
     */
    RSA_JDK(RSA, "SunRsaSign"),
    /**
     * ED25519 implementation using LibSodium JNI library.
     */
    ED25519_SODIUM(SigningSchema.ED25519, "LibSodium"),
    /**
     * ED25519 implementation using the default JDK security provider.
     */
    ED25519_SUN(SigningSchema.ED25519, "SunEC");

    private final SigningSchema signingSchema;
    private final String provider;

    /**
     * Constructor
     *
     * @param signingSchema the signing schema
     * @param provider      the security provider name
     */
    SigningImplementation(@NonNull final SigningSchema signingSchema, @NonNull final String provider) {
        this.signingSchema = signingSchema;
        this.provider = provider;
    }

    /**
     * Gets the signing schema
     *
     * @return the signing schema
     */
    public @NonNull SigningSchema getSigningSchema() {
        return signingSchema;
    }

    /**
     * Gets the security provider name. Note: this name may or may not correspond to an actual security provider
     * installed in the JVM. Some implementations use native libraries that do not register as security providers.
     *
     * @return the security provider name
     */
    public @NonNull String getProvider() {
        return provider;
    }
}
