// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.tss.SequentialContentManager;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class HintsKeyAccessorImpl
        implements HintsKeyAccessor,
                SequentialContentManager.ContentReader<Bytes>,
                SequentialContentManager.ContentWriter<Bytes> {
    private static final String SUB_DIRECTORY = "hints";
    private static final String FILE_CONTENT = "hinTS private key";
    private static final String FILE_NAME = "bls.bin";

    private final HintsLibrary library;
    // Volatile because lazy initialized and, in principle, could be accessed by multiple threads
    private volatile SequentialContentManager<Bytes> contentManager;
    private final Supplier<Configuration> config;
    private final Map<Long, Bytes> privateKeys = new ConcurrentHashMap<>();

    @Inject
    public HintsKeyAccessorImpl(@NonNull final HintsLibrary library, final Supplier<Configuration> config) {
        this.library = requireNonNull(library);
        this.config = config;
    }

    @Override
    public Bytes signWithBlsPrivateKey(final long constructionId, @NonNull final Bytes message) {
        final var key = getOrCreateBlsPrivateKey(constructionId);
        return library.signBls(message, key);
    }

    @Override
    public Bytes getOrCreateBlsPrivateKey(final long constructionId) {
        return privateKeys.computeIfAbsent(constructionId, contentManager()::getOrCreateContent);
    }

    private @NonNull SequentialContentManager<Bytes> contentManager() {
        if (contentManager == null) {
            synchronized (this) {
                if (contentManager == null) {
                    final var pathToKeys = getAbsolutePath(
                                    config.get().getConfigData(TssConfig.class).tssKeysPath())
                            .resolve(SUB_DIRECTORY);
                    contentManager = new SequentialContentManager<>(
                            pathToKeys, FILE_CONTENT, FILE_NAME, library::newBlsKeyPair, this, this);
                }
            }
        }
        return contentManager;
    }

    @NonNull
    @Override
    public Bytes readContent(@NonNull final Path p) throws IOException {
        return Bytes.wrap(Files.readAllBytes(p));
    }

    @Override
    public void writeContent(@NonNull final Bytes content, @NonNull final Path p) throws IOException {
        Files.write(p, content.toByteArray());
    }
}
