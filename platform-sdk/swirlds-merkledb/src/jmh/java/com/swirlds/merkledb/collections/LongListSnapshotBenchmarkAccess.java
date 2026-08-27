// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executor;

/** Gives the JMH benchmark access to the package-private snapshot durability switch. */
public final class LongListSnapshotBenchmarkAccess {

    private LongListSnapshotBenchmarkAccess() {}

    public static void writeToFile(
            final AbstractLongList<?> source,
            final Path file,
            final Executor executor,
            final int threadCount,
            final boolean forceToDisk)
            throws IOException {
        source.writeToFile(file, executor, threadCount, forceToDisk);
    }
}
