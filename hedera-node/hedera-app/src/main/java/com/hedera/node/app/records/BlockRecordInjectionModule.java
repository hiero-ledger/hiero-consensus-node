// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import com.hedera.node.app.records.impl.BlockRecordManagerImpl;
import com.hedera.node.app.records.impl.BlockRecordStreamProducer;
import com.hedera.node.app.records.impl.producers.BlockRecordFormat;
import com.hedera.node.app.records.impl.producers.BlockRecordWriterFactory;
import com.hedera.node.app.records.impl.producers.StreamFileProducerConcurrent;
import com.hedera.node.app.records.impl.producers.StreamFileProducerSingleThreaded;
import com.hedera.node.app.records.impl.producers.formats.BlockRecordWriterFactoryImpl;
import com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordFormatV6;
import com.hedera.node.app.records.impl.producers.formats.v7.BlockRecordFormatV7;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import javax.inject.Singleton;

/** A Dagger module for facilities in the {@link com.hedera.node.app.records} package. */
@Module
public abstract class BlockRecordInjectionModule {

    protected BlockRecordInjectionModule() {
        /* Nothing to do */
    }

    /** Provides the normal, default {@link java.nio.file.FileSystem}.*/
    @Provides
    @Singleton
    static FileSystem provideFileSystem() {
        return FileSystems.getDefault();
    }

    /**
     * Provides a {@link BlockRecordStreamProducer} based on the configuration. It is possible to use a concurrent producer,
     * or a single-threaded producer, based on configuration.
     */
    @Provides
    @Singleton
    public static BlockRecordStreamProducer provideStreamFileProducer(
            @NonNull final ConfigProvider configProvider,
            @NonNull final StreamFileProducerConcurrent concurrent,
            @NonNull final StreamFileProducerSingleThreaded serial) {
        final var recordStreamConfig = configProvider.getConfiguration().getConfigData(BlockRecordStreamConfig.class);
        final var producerType = recordStreamConfig.streamFileProducer().toUpperCase();
        return switch (producerType) {
            case "CONCURRENT" -> concurrent;
            case "SERIAL" -> serial;
            default -> throw new IllegalArgumentException("Unknown stream file producer type: " + producerType);
        };
    }

    /** Provides an implementation of the {@link com.hedera.node.app.records.BlockRecordManager}. */
    @Provides
    @Singleton
    public static BlockRecordManager provideBlockRecordManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final WorkingStateAccessor state,
            @NonNull final BlockRecordStreamProducer streamFileProducer) {
        final var merkleState = state.getState();
        if (merkleState == null) {
            throw new IllegalStateException("Merkle state is null");
        }
        return new BlockRecordManagerImpl(configProvider, merkleState, streamFileProducer);
    }

    @Provides
    @Singleton
    public static BlockRecordFormat provideBlockRecordFormat(@NonNull final ConfigProvider configProvider) {
        final var recordStreamConfig = configProvider.getConfiguration().getConfigData(BlockRecordStreamConfig.class);
        final var recordFileVersion = recordStreamConfig.recordFileVersion();
        return switch (recordFileVersion) {
            case BlockRecordFormatV6.VERSION_6 -> BlockRecordFormatV6.INSTANCE;
            case BlockRecordFormatV7.VERSION_7 -> BlockRecordFormatV7.INSTANCE;
            default -> throw new IllegalArgumentException("Unknown block record version: " + recordFileVersion);
        };
    }

    @Binds
    @Singleton
    public abstract BlockRecordWriterFactory provideBlockRecordWriterFactory(
            @NonNull BlockRecordWriterFactoryImpl impl);
}
