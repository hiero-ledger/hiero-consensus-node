// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Represents a type of writers for PCES files.
 */
public enum PcesFileWriterType {
    OUTPUT_STREAM,
    FILE_CHANNEL,
    FILE_CHANNEL_SYNC,
    OS_DEPENDENT;

    /**
     * Creates the right instance of the PcesFileWriter for the type represented by this enum
     *
     * @param path the path to the file to write to
     * @return the writer for writing PCES files
     * @throws IOException in case of error when creating the writer
     */
    public PcesFileWriter createWriter(@NonNull final Path path) throws IOException {
        final PcesFileWriterType selectedType;

        // performance of FILE_CHANNEL is 150x slower on MacOS, but marginally better on Linux; it is so bad on Mac
        // that basic tests cannot pass in some cases, so we need to make it system dependent, at same time allowing
        // override if needed
        if (this == OS_DEPENDENT) {
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                selectedType = PcesFileWriterType.OUTPUT_STREAM;
            } else {
                selectedType = PcesFileWriterType.FILE_CHANNEL_SYNC;
            }
        } else {
            selectedType = this;
        }
        return switch (selectedType) {
            case OUTPUT_STREAM -> new PcesOutputStreamFileWriter(path);
            case FILE_CHANNEL -> new PcesFileChannelWriter(path);
            case FILE_CHANNEL_SYNC -> new PcesFileChannelWriter(path, List.of(StandardOpenOption.DSYNC));
            case OS_DEPENDENT -> throw new IllegalStateException("Should never be called");
        };
    }
}
