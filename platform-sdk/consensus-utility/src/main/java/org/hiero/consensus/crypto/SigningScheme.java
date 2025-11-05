package org.hiero.consensus.crypto;

public enum SigningScheme {
    RSA(CryptoConstants.SIG_TYPE1,
            CryptoConstants.SIG_KEY_SIZE_BITS, CryptoConstants.SIG_TYPE2),
    EC("EC", 384, "SHA384withECDSA"),
    ED25519("Ed25519", 255, "Ed25519");

    private final String keyType;
    private final String signingAlgorithm;
    private final int keySizeBits;

    SigningScheme(final String keyType, final int keySizeBits, final String signingAlgorithm) {
        this.keyType = keyType;
        this.signingAlgorithm = signingAlgorithm;
        this.keySizeBits = keySizeBits;
    }

    public String getKeyType() {
        return keyType;
    }

    public String getSigningAlgorithm() {
        return signingAlgorithm;
    }


    public int getKeySizeBits() {
        return keySizeBits;
    }
}
