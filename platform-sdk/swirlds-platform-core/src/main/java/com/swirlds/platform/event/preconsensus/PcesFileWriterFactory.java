// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.hiero.consensus.event.intake.config.PcesFileWriterType;

/**
 * Factory for creating PcesFileWriter instances
 */
public class PcesFileWriterFactory {

    private PcesFileWriterFactory() {}

    /**
     * Creates the right instance of the PcesFileWriter for the type represented by this enum
     *
     * @param writerType the type of writer to create
     * @param path the path to the file to write to
     * @return the writer for writing PCES files
     * @throws IOException in case of error when creating the writer
     */
    public static PcesFileWriter createWriter(@NonNull final PcesFileWriterType writerType, @NonNull final Path path)
            throws IOException {
        return switch (writerType) {
            case OUTPUT_STREAM -> new PcesOutputStreamFileWriter(path);
            case FILE_CHANNEL -> new PcesFileChannelWriter(path);
            case FILE_CHANNEL_SYNC -> new PcesFileChannelWriter(path, List.of(StandardOpenOption.DSYNC));
        };
    }
}
