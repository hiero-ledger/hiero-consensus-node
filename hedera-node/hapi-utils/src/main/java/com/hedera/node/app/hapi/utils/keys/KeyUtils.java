// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.keys;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.security.DrbgParameters;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.OperatorCreationException;

/**
 * Utility class for working with algorithm-agnostic cryptographic keys
 */
public final class KeyUtils {
    public static final Provider BC_PROVIDER = new BouncyCastleProvider();
    public static final String TEST_CLIENTS_PREFIX = "hedera-node" + File.separator + "test-clients" + File.separator;

    private static final int ENCRYPTOR_ITERATION_COUNT = 10_000;
    private static final String RESOURCE_PATH_SEGMENT = "src/main/resource";
    private static final DrbgParameters.Instantiation DRBG_INSTANTIATION =
            DrbgParameters.instantiation(256, DrbgParameters.Capability.RESEED_ONLY, null);

    public static Path relocatedIfNotPresentInWorkingDir(final Path path) {
        return relocatedIfNotPresentInWorkingDir(path.toFile()).toPath();
    }

    public static File relocatedIfNotPresentInWorkingDir(final File file) {
        return relocatedIfNotPresentWithCurrentPathPrefix(file, RESOURCE_PATH_SEGMENT, TEST_CLIENTS_PREFIX);
    }

    public static void writeKeyTo(final PrivateKey key, final String pemLoc, final String passphrase) {
        final var pem = new File(pemLoc);
        try (final var out = new FileOutputStream(pem)) {
            final var random = SecureRandom.getInstance("DRBG", DRBG_INSTANTIATION);
            final var encryptor = new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC)
                    .setPRF(PKCS8Generator.PRF_HMACSHA384)
                    .setIterationCount(ENCRYPTOR_ITERATION_COUNT)
                    .setRandom(random)
                    .setPassword(passphrase.toCharArray())
                    .setProvider(BC_PROVIDER)
                    .build();
            try (final var pemWriter = new JcaPEMWriter(new OutputStreamWriter(out))) {
                pemWriter.writeObject(new JcaPKCS8Generator(key, encryptor).generate());
                pemWriter.flush();
            }
        } catch (final IOException | NoSuchAlgorithmException | OperatorCreationException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static File relocatedIfNotPresentWithCurrentPathPrefix(
            final File file, final String firstSegmentToRelocate, final String newPathPrefix) {
        if (!file.exists()) {
            final var absPath = withDedupedHederaNodePathSegments(file.getAbsolutePath());
            final var idx = absPath.indexOf(firstSegmentToRelocate);
            if (idx == -1) {
                return new File(absPath);
            }
            final var relocatedPath = newPathPrefix + absPath.substring(idx);
            return new File(relocatedPath);
        } else {
            return file;
        }
    }

    private static String withDedupedHederaNodePathSegments(@NonNull final String loc) {
        final var firstSegmentI = loc.indexOf("hedera-node");
        if (firstSegmentI == -1) {
            return loc;
        }
        final var lastSegmentI = loc.lastIndexOf("hedera-node");
        if (lastSegmentI != firstSegmentI) {
            return loc.substring(0, firstSegmentI) + loc.substring(lastSegmentI);
        } else {
            return loc;
        }
    }

    private KeyUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }
}
