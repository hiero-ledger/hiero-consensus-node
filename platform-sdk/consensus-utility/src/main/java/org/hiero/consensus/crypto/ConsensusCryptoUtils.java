// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static org.hiero.base.crypto.KeystorePasswordPolicy.warnIfNonCompliant;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.base.crypto.config.CryptoConfig;
import org.hiero.base.crypto.config.CryptoConfig_;

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
    @NonNull
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
    @NonNull
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

    /**
     * Retrieves the keystore password from the configuration and logs a warning if it does not meet the recommended
     * password policy to help operators detect insecure configuration.
     *
     * @param configuration the configuration to retrieve the keystore password from
     * @return the keystore password from the configuration
     * @throws IllegalStateException if the keystore password is {@code null} or blank
     */
    @NonNull
    public static String getConfiguredKeystorePassword(@NonNull final Configuration configuration) {
        final CryptoConfig configData = configuration.getConfigData(CryptoConfig.class);
        final String passphrase = configData.keystorePassword();
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalStateException(CryptoConfig_.KEYSTORE_PASSWORD + " must not be null or blank");
        }
        warnIfNonCompliant(CryptoConfig_.KEYSTORE_PASSWORD, passphrase);

        return passphrase;
    }

    /**
     * Create a KeyManagerFactory for TLS connections, using the given configuration and keys and certificates. The
     * KeyManagerFactory will be initialized with a KeyStore that contains the private key and certificate for this node.
     *
     * @param certificate the certificate to use
     * @param privateKey the private key to use
     * @param configuration the configuration to use for getting the password for the KeyStore
     * @return the {@link KeyManagerFactory}
     */
    @NonNull
    public static KeyManagerFactory createKeyManagerFactory(
            @NonNull final Certificate certificate,
            @NonNull final PrivateKey privateKey,
            @NonNull final Configuration configuration)
            throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
        final char[] password = getConfiguredKeystorePassword(configuration).toCharArray();

        // the agrKeyStore should contain an entry with both the private key and the certificate
        final KeyStore agrKeyStore = ConsensusCryptoUtils.createEmptyTrustStore();
        agrKeyStore.setKeyEntry("key", privateKey, password, new Certificate[] {certificate});

        // "PKIX" may be more interoperable than KeyManagerFactory.getDefaultAlgorithm or
        final KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(CryptoConstants.KEY_MANAGER_FACTORY_TYPE);
        keyManagerFactory.init(agrKeyStore, password);

        return keyManagerFactory;
    }
}
