// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

/**
 * Interface for writing events to a file.
 */
public interface PcesFileWriter {

    /**
     * Write the version of the file format to the file.
     *
     * @param version the version of the file format
     */
    void writeVersion(final int version) throws IOException;

    /**
     * Write an event to the file.
     *
     * @param event the event to write
     */
    void writeEvent(@NonNull final GossipEvent event) throws IOException;

    /**
     * Flush the file.
     */
    void flush() throws IOException;

    /**
     * Sync the buffer with the file system.
     */
    void sync() throws IOException;

    /**
     * Close the file.
     */
    void close() throws IOException;

    /**
     * @return the size of the file in bytes
     */
    long fileSize();

    /** Keeps stats of the writing process */
    PcesFileWriterStats getStats();
}
