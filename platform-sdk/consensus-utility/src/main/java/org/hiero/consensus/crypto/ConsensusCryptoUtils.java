// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.CryptographyException;

/**
 * Utility methods for cryptographic operations
 */
public class ConsensusCryptoUtils {

    private static final Logger logger = LogManager.getLogger();

    private ConsensusCryptoUtils() {}

    /**
     * See {@link SignatureVerifier#verifySignature(Bytes, Bytes, PublicKey)}
     */
    public static boolean verifySignature(
            @NonNull final Bytes data, @NonNull final Bytes signature, @NonNull final PublicKey publicKey) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(signature);
        Objects.requireNonNull(publicKey);
        try {
            return SigningFactory.createVerifier(publicKey).verify(data, signature);
        } catch (final CryptographyException e) {
            logger.error(LogMarker.EXCEPTION.getMarker(), "Exception occurred while validating a signature:", e);
            return false;
        }
    }

    /**
     * Return the nondeterministic secure random number generator stored in this Crypto instance. If it doesn't already
     * exist, create it.
     *
     * @return the stored SecureRandom object
     */
    public static SecureRandom getNonDetRandom() {
        final SecureRandom nonDetRandom;
        try {
            nonDetRandom = SecureRandom.getInstanceStrong();
        } catch (final NoSuchAlgorithmException e) {
            throw new CryptographyException(e, EXCEPTION);
        }
        // call nextBytes before setSeed, because some algorithms (like SHA1PRNG) become
        // deterministic if you don't. This call might hang if the OS has too little entropy
        // collected. Or it might be that nextBytes doesn't hang but getSeed does. The behavior is
        // different for different choices of OS, Java version, and JDK library implementation.
        nonDetRandom.nextBytes(new byte[1]);
        return nonDetRandom;
    }

    /**
     * Create a new trust store that is initially empty, but will later have all the members' key agreement public key
     * certificates added to it.
     *
     * @return the empty KeyStore to be used as a trust store for TLS for syncs.
     * @throws KeyStoreException if there is no provider that supports {@link CryptoConstants#KEYSTORE_TYPE}
     */
    public static KeyStore createEmptyTrustStore() throws KeyStoreException {
        final KeyStore trustStore;
        try {
            trustStore = KeyStore.getInstance(CryptoConstants.KEYSTORE_TYPE);
            trustStore.load(null);
        } catch (final CertificateException | IOException | NoSuchAlgorithmException e) {
            // cannot be thrown when calling load(null)
            throw new CryptographyException(e);
        }
        return trustStore;
    }
}
