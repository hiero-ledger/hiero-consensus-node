// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.records.impl.producers.BlockRecordFormat.TAG_TYPE_BITS;
import static com.hedera.node.app.records.impl.producers.BlockRecordFormat.WIRE_TYPE_DELIMITED;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Appends {@link WrappedRecordFileBlockHashes} entries to a single on-disk file.
 *
 * <p>The file format is an append-only sequence of protobuf-framed occurrences of field {@code entries} from the
 * {@code WrappedRecordFileBlockHashesLog} container message, i.e. repeated occurrences of
 * {@code (tag=1, wire=length-delimited) + length + bytes(WrappedRecordFileBlockHashes)}.
 */
@Singleton
public class WrappedRecordFileBlockHashesDiskWriter {
    private static final Logger logger = LogManager.getLogger(WrappedRecordFileBlockHashesDiskWriter.class);

    private static final String DEFAULT_FILE_NAME = "wrapped-record-hashes.pb";

    /**
     * Field number for {@code WrappedRecordFileBlockHashesLog.entries}.
     */
    private static final int ENTRIES_FIELD_NUMBER = 1;

    private final ConfigProvider configProvider;
    private final FileSystem fileSystem;

    @Inject
    public WrappedRecordFileBlockHashesDiskWriter(
            @NonNull final ConfigProvider configProvider, @NonNull final FileSystem fileSystem) {
        this.configProvider = requireNonNull(configProvider);
        this.fileSystem = requireNonNull(fileSystem);
    }

    /**
     * Appends a single entry to the on-disk log file. Any I/O failure is logged; the exception is not propagated.
     *
     * @param entry the entry to append
     */
    public void append(@NonNull final WrappedRecordFileBlockHashes entry) {
        requireNonNull(entry);
        final var cfg = configProvider.getConfiguration().getConfigData(BlockRecordStreamConfig.class);
        final Path dir = fileSystem.getPath(cfg.wrappedRecordHashesDir());
        final Path file = dir.resolve(DEFAULT_FILE_NAME);

        try {
            Files.createDirectories(dir);
            try (final var out = Files.newOutputStream(
                            file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                    final var buffered = new BufferedOutputStream(out);
                    final var stream = new WritableStreamingData(buffered)) {
                final var bytes = WrappedRecordFileBlockHashes.PROTOBUF.toBytes(entry);
                stream.writeVarInt((ENTRIES_FIELD_NUMBER << TAG_TYPE_BITS) | WIRE_TYPE_DELIMITED, false);
                stream.writeVarInt((int) bytes.length(), false);
                stream.writeBytes(bytes);
            }
        } catch (final IOException | UncheckedIOException e) {
            logger.error(
                    "Failed to append wrapped record-file block hashes for block {} to {}",
                    entry.blockNumber(),
                    file,
                    e);
        }
    }
}
