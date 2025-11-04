package org.hiero.consensus.crypto;

public enum SigningAlgorithm {
    RSA_BC(CryptoConstants.SIG_TYPE1,
            CryptoConstants.SIG_TYPE2,
            CryptoConstants.SIG_PROVIDER,
            CryptoConstants.SIG_KEY_SIZE_BITS),
    RSA_SUN(CryptoConstants.SIG_TYPE1,
            CryptoConstants.SIG_TYPE2,
            "SunRsaSign",
            CryptoConstants.SIG_KEY_SIZE_BITS),
    EC_SUN(CryptoConstants.AGR_TYPE,
            "SHA384withECDSA",
            "SunEC",
            CryptoConstants.AGR_KEY_SIZE_BITS),
    ED25519_SODIUM(
            "Ed25519",
            "Ed25519",
            "SunEC",
            255),
    ED25519_SUN("Ed25519",
            "Ed25519",
            "SunEC",
            255);

    private final String keyType;
    private final String signingAlgorithm;
    private final String provider;
    private final int keySizeBits;

    SigningAlgorithm(final String keyType, final String signingAlgorithm, final String provider,
            final int keySizeBits) {
        this.keyType = keyType;
        this.signingAlgorithm = signingAlgorithm;
        this.provider = provider;
        this.keySizeBits = keySizeBits;
    }

    public String getKeyType() {
        return keyType;
    }

    public String getSigningAlgorithm() {
        return signingAlgorithm;
    }

    public String getProvider() {
        return provider;
    }

    public int getKeySizeBits() {
        return keySizeBits;
    }
}
