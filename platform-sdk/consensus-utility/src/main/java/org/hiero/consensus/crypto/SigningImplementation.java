package org.hiero.consensus.crypto;

import static org.hiero.consensus.crypto.SigningScheme.RSA;

public enum SigningImplementation {
    RSA_BC(RSA, CryptoConstants.SIG_PROVIDER),
    RSA_JDK(RSA, "SunRsaSign"),
    EC_JDK(SigningScheme.EC, "SunEC"),
    ED25519_SODIUM(SigningScheme.ED25519, "LibSodium"),
    ED25519_SUN(SigningScheme.ED25519, "SunEC");

    private final SigningScheme signingScheme;
    private final String provider;

    SigningImplementation(final SigningScheme signingScheme, final String provider) {
        this.signingScheme = signingScheme;
        this.provider = provider;
    }

    public SigningScheme getSigningScheme() {
        return signingScheme;
    }

    public String getProvider() {
        return provider;
    }
}
