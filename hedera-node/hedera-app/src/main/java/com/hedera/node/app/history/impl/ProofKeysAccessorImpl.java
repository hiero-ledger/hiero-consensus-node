// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.rpm.SigningAndVerifyingSchnorrKeys;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.tss.SequentialContentManager;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides stable access to Schnorr key pairs for use in history proofs.
 */
@Singleton
public class ProofKeysAccessorImpl
        implements ProofKeysAccessor,
                SequentialContentManager.ContentReader<ProofKeysAccessorImpl.SchnorrKeyPair>,
                SequentialContentManager.ContentWriter<ProofKeysAccessorImpl.SchnorrKeyPair> {
    private static final String SUB_DIRECTORY = "wraps";
    private static final String FILE_CONTENT = "Schnorr key pair";
    private static final String FILE_NAME = "schnorr.bin";

    private final HistoryLibrary library;
    private final Supplier<Configuration> config;

    // Volatile because lazy initialized and, in principle, could be accessed by multiple threads
    private volatile SequentialContentManager<SchnorrKeyPair> contentManager;

    @Inject
    public ProofKeysAccessorImpl(@NonNull final HistoryLibrary library, @NonNull final Supplier<Configuration> config) {
        this.config = requireNonNull(config);
        this.library = requireNonNull(library);
    }

    @Override
    public Bytes sign(final long constructionId, @NonNull final Bytes message) {
        requireNonNull(message);
        final var keyPair = contentManager().getOrCreateContent(constructionId);
        return library.signSchnorr(message, keyPair.privateKey());
    }

    @Override
    public SchnorrKeyPair getOrCreateSchnorrKeyPair(final long constructionId) {
        return contentManager().getOrCreateContent(constructionId);
    }

    @NonNull
    @Override
    public SchnorrKeyPair readContent(@NonNull final Path p) throws IOException {
        requireNonNull(p);
        final var bytes = Files.readAllBytes(p);
        return SchnorrKeyPair.fromDelimited(bytes);
    }

    @Override
    public void writeContent(@NonNull final SchnorrKeyPair content, @NonNull final Path p) throws IOException {
        requireNonNull(content);
        requireNonNull(p);
        Files.write(p, content.toDelimitedBytes());
    }

    public record SchnorrKeyPair(Bytes privateKey, Bytes publicKey) {
        /**
         * Translates a {@link SigningAndVerifyingSchnorrKeys} instance into a {@link SchnorrKeyPair}.
         * @param keys the instance to translate
         * @return the translated instance
         */
        public static SchnorrKeyPair from(@NonNull final SigningAndVerifyingSchnorrKeys keys) {
            return new SchnorrKeyPair(Bytes.wrap(keys.signingKey()), Bytes.wrap(keys.verifyingKey()));
        }

        /**
         * Translates a byte array into a {@link SchnorrKeyPair} instance.
         * @param bytes the byte array
         * @return the instance
         */
        public static SchnorrKeyPair fromDelimited(@NonNull final byte[] bytes) {
            final var m = bytes[0];
            final var privateKey = new byte[m];
            System.arraycopy(bytes, 1, privateKey, 0, m);
            final var n = bytes[m + 1];
            final var publicKey = new byte[n];
            System.arraycopy(bytes, m + 2, publicKey, 0, n);
            return new SchnorrKeyPair(Bytes.wrap(privateKey), Bytes.wrap(publicKey));
        }

        /**
         * Converts this instance into a byte array with the following format:
         * <ul>
         *     <li>1 byte for the length of the private key</li>
         *     <li>the private key</li>
         *     <li>1 byte for the length of the public key</li>
         *     <li>the public key</li>
         * </ul>
         * @return the byte array
         */
        public byte[] toDelimitedBytes() {
            final int m = (int) privateKey.length();
            final int n = (int) publicKey.length();
            final var bytes = new byte[2 + m + n];
            bytes[0] = (byte) m;
            System.arraycopy(privateKey.toByteArray(), 0, bytes, 1, m);
            bytes[m + 1] = (byte) n;
            System.arraycopy(publicKey.toByteArray(), 0, bytes, m + 2, n);
            return bytes;
        }
    }

    private @NonNull SequentialContentManager<SchnorrKeyPair> contentManager() {
        if (contentManager == null) {
            synchronized (this) {
                if (contentManager == null) {
                    final var pathToKeys = getAbsolutePath(
                                    config.get().getConfigData(TssConfig.class).tssKeysPath())
                            .resolve(SUB_DIRECTORY);
                    contentManager = new SequentialContentManager<>(
                            pathToKeys,
                            FILE_CONTENT,
                            FILE_NAME,
                            () -> SchnorrKeyPair.from(library.newSchnorrKeyPair()),
                            this,
                            this);
                }
            }
        }
        return contentManager;
    }
}
