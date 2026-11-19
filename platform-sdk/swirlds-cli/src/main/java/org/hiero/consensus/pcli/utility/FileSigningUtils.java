// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Objects;

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
