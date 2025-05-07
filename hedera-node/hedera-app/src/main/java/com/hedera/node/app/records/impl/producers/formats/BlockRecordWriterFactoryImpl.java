// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.records.impl.producers.BlockRecordWriter;
import com.hedera.node.app.records.impl.producers.BlockRecordWriterFactory;
import com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordWriterV6;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.FileSystem;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.base.crypto.Signer;

@Singleton
public class BlockRecordWriterFactoryImpl implements BlockRecordWriterFactory {
    private final ConfigProvider configProvider;
    private final Signer signer;
    private final NodeInfo selfNodeInfo;
    private final FileSystem fileSystem;

    /**
     *
     * @param configProvider
     * @param fileSystem the file system to use, needed for testing to be able to use a non-standard file
     *                   system. If null default is used.
     */
    @Inject
    public BlockRecordWriterFactoryImpl(
            @NonNull final ConfigProvider configProvider,
            @NonNull final NodeInfo selfNodeInfo,
            @NonNull final Signer signer,
            @NonNull final FileSystem fileSystem) {
        this.configProvider = requireNonNull(configProvider);
        this.fileSystem = requireNonNull(fileSystem);
        this.selfNodeInfo = requireNonNull(selfNodeInfo);
        this.signer = requireNonNull(signer);
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
                        selfNodeInfo,
                        signer,
                        fileSystem);
            case 7 -> throw new IllegalArgumentException("Record file version 7 is not yet supported");
            default -> throw new IllegalArgumentException("Unknown record file version: " + recordFileVersion);
        };
    }
}
