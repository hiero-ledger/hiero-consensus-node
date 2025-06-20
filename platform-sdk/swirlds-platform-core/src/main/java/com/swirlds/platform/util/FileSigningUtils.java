// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Objects;
import org.hiero.base.crypto.SignatureType;

/**
 * Class containing utilities for creating signature files
 */
public final class FileSigningUtils {
    /**
     * Hidden constructor for utility class
     */
    private FileSigningUtils() {}

    /**
     * The suffix to append to file name when generating corresponding signature file
     */
    public static final String SIGNATURE_FILE_NAME_SUFFIX = "_sig";

    /**
     * Type of the keyStore
     */
    private static final String KEYSTORE_TYPE = "pkcs12";

    /**
     * Builds a signature file path from a destination directory and source file
     * <p>
     * Creates the needed directories if they don't already exist
     *
     * @param destinationDirectory the directory to which the signature file is saved
     * @param sourceFile           file to be signed
     * @return signature file path
     */
    @NonNull
    public static Path buildSignatureFilePath(
            @NonNull final Path destinationDirectory, @NonNull final Path sourceFile) {

        Objects.requireNonNull(destinationDirectory, "destinationDirectory must not be null");
        Objects.requireNonNull(sourceFile, "sourceFile must not be null");

        try {
            Files.createDirectories(destinationDirectory);
        } catch (final IOException e) {
            throw new RuntimeException("Failed to create directory", e);
        }

        return destinationDirectory.resolve(sourceFile.getFileName() + SIGNATURE_FILE_NAME_SUFFIX);
    }

    /**
     * Sign a byte array with a private key
     *
     * @param data    the data to be signed
     * @param keyPair the keyPair used for signing
     * @return the signature
     * @throws NoSuchAlgorithmException if an implementation of the required algorithm cannot be located or loaded
     * @throws NoSuchProviderException  thrown if the specified provider is not registered in the security provider
     *                                  list
     * @throws InvalidKeyException      thrown if the key is invalid
     * @throws SignatureException       thrown if this signature object is not initialized properly
     */
    @NonNull
    public static byte[] signData(@NonNull final byte[] data, @NonNull final KeyPair keyPair)
            throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeyException, SignatureException {

        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(keyPair, "keyPair must not be null");

        final Signature signature =
                Signature.getInstance(SignatureType.RSA.signingAlgorithm(), SignatureType.RSA.provider());

        signature.initSign(keyPair.getPrivate());
        signature.update(data);

        return signature.sign();
    }

    /**
     * Loads a pfx key from file and returns the key pair
     *
     * @param keyFile  a pfx key file
     * @param password the password for the key file
     * @param alias    alias of the key
     * @return a KeyPair
     */
    @NonNull
    public static KeyPair loadPfxKey(
            @NonNull final Path keyFile, @NonNull final String password, @NonNull final String alias) {

        Objects.requireNonNull(keyFile, "keyFile must not be null");
        Objects.requireNonNull(password, "password must not be null");
        Objects.requireNonNull(alias, "alias must not be null");

        try (final InputStream inputStream = Files.newInputStream(keyFile)) {
            final KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
            keyStore.load(inputStream, password.toCharArray());

            return new KeyPair(keyStore.getCertificate(alias).getPublicKey(), (PrivateKey)
                    keyStore.getKey(alias, password.toCharArray()));
        } catch (final NoSuchAlgorithmException
                | KeyStoreException
                | UnrecoverableKeyException
                | IOException
                | CertificateException e) {

            throw new RuntimeException("Unable to load Pfx key from file: " + keyFile, e);
        }
    }
}
