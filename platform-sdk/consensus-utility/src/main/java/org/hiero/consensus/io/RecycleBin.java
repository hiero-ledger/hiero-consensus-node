// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.io;

import com.swirlds.base.state.Startable;
import com.swirlds.base.state.Stoppable;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;

/**
 * This class provides the abstraction of deleting a file, but actually moves the file to a temporary location in case
 * the file becomes useful later for debugging.
 * <p>
 * Data moved to the recycle bin persist in the temporary location for an unspecified amount of time, perhaps even no
 * time at all. Files in this temporary location may be deleted at any time without warning. It is never ok to write
 * code that depends on the existence of files in this temporary location. Files in this temporary location should be
 * treated as deleted by java code, and only used for debugging purposes.
 */
public interface RecycleBin extends Startable, Stoppable {

    /**
     * Remove a file or directory tree from its current location and move it to a temporary location.
     * <p>
     * Recycled data will persist in the temporary location for an unspecified amount of time, perhaps even no time at
     * all. Files in this temporary location may be deleted at any time without warning. It is never ok to write code
     * that depends on the existence of files in this temporary location. Files in this temporary location should be
     * treated as deleted by java code, and only used for debugging purposes.
     *
     * @param path the file or directory to recycle
     * @throws IOException if an I/O error occurs
     */
    void recycle(@NonNull Path path) throws IOException;
}
