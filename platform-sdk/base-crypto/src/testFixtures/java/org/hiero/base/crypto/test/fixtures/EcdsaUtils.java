// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.test.fixtures;

import static org.hiero.base.utility.CommonUtils.unhex;

import com.hedera.cryptography.libsecp256k1.ContextualLibsecp256k1;
import com.hedera.cryptography.libsecp256k1.Libsecp256k1;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import org.hiero.base.crypto.ECDSASecp256k1;

/**
 * Utility class for ECDSA keys
 */
public final class EcdsaUtils {
    private static final ContextualLibsecp256k1 LIBSECP256K1 = ContextualLibsecp256k1.getInstance();

    public static final int SIGNATURE_LENGTH = Libsecp256k1.SIGNATURE_BYTES;
    /* A digest value re-used in several tests. */
    public static final byte[] WELL_KNOWN_DIGEST =
            unhex("744c77a7af70b3a522009f0a963384eccfa77662a594d6e0247dfba095eb48d5");

    private EcdsaUtils() {}

    /**
     * Signs the well-known digest with the given ECDSA(secp256k1) private key and returns the
     * raw signature; that is, the 64-byte concatenation of the signature's {@code r} value
     * followed by its {@code s} value as unsigned big-endians.
     *
     * @param privateKey
     * 		the private key to use
     * @return the raw signature of the well-known digest
     */
    public static byte[] signWellKnownDigestWithEcdsaSecp256k1(final PrivateKey privateKey) {
        return ECDSASecp256k1.signDigestWithEcdsaSecp256k1(privateKey, WELL_KNOWN_DIGEST);
    }

    /**
     * Returns the 64-byte raw form of a given ECDSA(secp256k1) public key; that is, the 64-byte
     * concatenation of the public key's x- and y-coordinates in unsigned big-endian form.
     *
     * @param cryptoPubKey
     * 		the public key to encode
     * @return the raw coordinates of the public key
     */
    public static byte[] asRawEcdsaSecp256k1Key(final ECPublicKey cryptoPubKey) {
        final byte[] encPubKey = cryptoPubKey.getEncoded();
        return Arrays.copyOfRange(encPubKey, encPubKey.length - SIGNATURE_LENGTH, encPubKey.length);
    }
}
