// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A simple implementation of a recycle bin that deletes files permanently immediately.
 */
public class SimpleRecycleBin implements RecycleBin {
    @Override
    public void recycle(@NonNull final Path path) throws IOException {
        // deletes files as well, even though the name might be misleading
        FileUtils.deleteDirectory(path);
    }

    @Override
    public void start() {
        // nothing to do here
    }

    @Override
    public void stop() {
        // nothing to do here
    }
}
