// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures;

import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.RecycleBin;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * An implementation of a {@link RecycleBin} that immediately deletes recycled files. Technically speaking this
 * is not a violation of the recycle bin contract. Handy for test scenarios where you don't want to worry about
 * cleaning up the recycle bin.
 */
public class TestRecycleBin implements RecycleBin {

    private static final TestRecycleBin INSTANCE = new TestRecycleBin();
    public static final Duration MAXIMUM_FILE_AGE = Duration.of(7, ChronoUnit.DAYS);
    public static final Duration MINIMUM_PERIOD = Duration.of(1, ChronoUnit.DAYS);

    /**
     * Get the singleton instance of this class.
     */
    public static TestRecycleBin getInstance() {
        return INSTANCE;
    }

    private TestRecycleBin() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void recycle(@NonNull final Path path) throws IOException {
        FileUtils.deleteDirectory(path);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {}

    /**
     * Stop this object.
     */
    @Override
    public void stop() {}
}
