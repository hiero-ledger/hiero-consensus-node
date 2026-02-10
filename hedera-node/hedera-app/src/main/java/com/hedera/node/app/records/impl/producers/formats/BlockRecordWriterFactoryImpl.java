// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.records.impl.producers.BlockRecordWriter;
import com.hedera.node.app.records.impl.producers.BlockRecordWriterFactory;
import com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordWriterV6;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.FileSystem;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Signer;

@Singleton
public class BlockRecordWriterFactoryImpl implements BlockRecordWriterFactory {
    private static final Logger logger = LogManager.getLogger(BlockRecordWriterFactoryImpl.class);
    private final ConfigProvider configProvider;
    private final Signer signer;
    private final FileSystem fileSystem;
    private final SelfNodeAccountIdManager selfNodeAccountIdManager;

    /**
     *
     * @param configProvider
     * @param fileSystem the file system to use, needed for testing to be able to use a non-standard file
     *                   system. If null default is used.
     */
    @Inject
    public BlockRecordWriterFactoryImpl(
            @NonNull final ConfigProvider configProvider,
            @NonNull final Signer signer,
            @NonNull final FileSystem fileSystem,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager) {
        this.configProvider = requireNonNull(configProvider);
        this.fileSystem = requireNonNull(fileSystem);
        this.signer = requireNonNull(signer);
        this.selfNodeAccountIdManager = selfNodeAccountIdManager;
    }

    @Override
    public BlockRecordWriter create() throws RuntimeException {
        // read configuration
        final var config = configProvider.getConfiguration();
        final var recordStreamConfig = config.getConfigData(BlockRecordStreamConfig.class);
        final var recordFileVersion = recordStreamConfig.recordFileVersion();

        // pick a record file format
        return switch (recordFileVersion) {
            case 6 ->
                new BlockRecordWriterV6(
                        configProvider.getConfiguration().getConfigData(BlockRecordStreamConfig.class),
                        selfNodeAccountIdManager.getSelfNodeAccountId(),
                        signer,
                        fileSystem);
            case 7 -> throw new IllegalArgumentException("Record file version 7 is not yet supported");
            default -> throw new IllegalArgumentException("Unknown record file version: " + recordFileVersion);
        };
    }
}
