// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import java.security.Security;
import java.time.Instant;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;

public final class CryptoConstants {
    // number of bytes in a hash
    public static final int HASH_SIZE_BYTES = 48; // 384 bits (= 3*128)
    // size (in bits) of a public or private key
    public static final int SIG_KEY_SIZE_BITS = 3072;
    public static final int AGR_KEY_SIZE_BITS = 384; // 3*128 bits
    // max number of bytes in a signature
    // this might be as high as 16+2*ceiling(KEY_SIZE_BITS/8), but is 8 less than that here
    public static final int SIG_SIZE_BYTES = 384;
    // size of each symmetric key, in bytes
    public static final int SYM_KEY_SIZE_BYTES = 32; // 256 bits
    // the algorithms and providers to use (AGR is key agreement, SIG is signatures)
    public static final String AGR_TYPE = "EC";
    public static final String AGR_PROVIDER = "SunEC";
    public static final String SIG_TYPE1 = "RSA"; // or SHA384withRSA
    public static final String SIG_PROVIDER = getBCProviderName();
    public static final String SIG_TYPE2 = "SHA384withRSAandMGF1"; // RSA-PSS, required for TLS 1.3 cert chains
    /** this is the only TLS protocol we will allow */
    public static final String TLS_SUITE = "TLS_AES_256_GCM_SHA384";
    // certificate settings
    public static final Instant DEFAULT_VALID_FROM = Instant.parse("2000-01-01T00:00:00Z");
    public static final Instant DEFAULT_VALID_TO = Instant.parse("2100-01-01T00:00:00Z");
    // SSL settings
    public static final String KEY_MANAGER_FACTORY_TYPE = "PKIX";
    public static final String KEY_MANAGER_FACTORY_PROVIDER = "BCJSSE";
    public static final String TRUST_MANAGER_FACTORY_TYPE = "PKIX";
    public static final String TRUST_MANAGER_FACTORY_PROVIDER = "BCJSSE";
    public static final String SSL_VERSION = "TLSv1.3";
    public static final String SSL_PROVIDER = "BCJSSE";
    // TLS 1.3 named groups: X25519MLKEM768 hybrid PQ key exchange, secp384r1 for ECDSA signature scheme activation
    public static final String[] TLS_NAMED_GROUPS = {"X25519MLKEM768", "secp384r1"};
    public static final String[] TLS_SIGNATURE_SCHEMES = {
            "ecdsa_secp384r1_sha384",
            "ecdsa_secp256r1_sha256",
            "ed25519",
            "ed448",
            "rsa_pss_rsae_sha256",
            "rsa_pss_rsae_sha384",
    };
    // keystore settings
    public static final String KEYSTORE_TYPE = "BKS";
    public static final String KEYSTORE_PROVIDER = "BC";

    private CryptoConstants() {}

    /* Ensure BouncyCastle providers are added before the names are used.
     * BC provider must be passed explicitly to BCJSSE so its JcaTlsCrypto uses BC for ML-KEM.
     * Without this, BCJSSE's DefaultJcaJceHelper finds JDK 25's SunJCE ML-KEM which rejects
     * BC's MLKEMParameterSpec, causing all ML-KEM named groups to be silently disabled. */
    private static String getBCProviderName() {
        final BouncyCastleProvider bcProv = new BouncyCastleProvider();
        Security.addProvider(bcProv);
        Security.addProvider(new BouncyCastleJsseProvider(bcProv));
        return BouncyCastleProvider.PROVIDER_NAME;
    }
}
