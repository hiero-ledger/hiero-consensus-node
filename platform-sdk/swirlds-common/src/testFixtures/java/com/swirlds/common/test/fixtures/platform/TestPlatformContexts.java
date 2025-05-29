// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.platform;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.io.utility.RecycleBinImpl;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.TestRecycleBin;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.event.preconsensus.PcesConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A utility class for generating PlatformContexts.
 */
public class TestPlatformContexts {

    /**
     * Creates a context with no recycle bin and no metrics.
     * @param time The time
     * @param dataDir The directory where data is placed
     * @return a platformContext
     */
    @NonNull
    public static PlatformContext context(@NonNull final Time time, final Path dataDir) {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, dataDir)
                .withValue(PcesConfig_.PREFERRED_FILE_SIZE_MEGABYTES, 5)
                .withValue(PcesConfig_.PERMIT_GAPS, false)
                .withValue(PcesConfig_.COMPACT_LAST_FILE_ON_STARTUP, false)
                .getOrCreateConfig();

        return TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withTime(time)
                .build();
    }

    /**
     * Creates a context.
     * @param permitGaps  Whether gaps are permitted when reading pces files
     * @param recycleBinPath The directory where recycle bin is placed
     * @param dataDir The directory where data is placed
     * @param fsDirectory the directory for the file-system
     * @return a platformContext
     */
    @NonNull
    public static PlatformContext context(
            final boolean permitGaps,
            @Nullable final Path recycleBinPath,
            @NonNull final Path dataDir,
            @Nullable final Path fsDirectory) {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, dataDir)
                .withValue(PcesConfig_.PREFERRED_FILE_SIZE_MEGABYTES, 5)
                .withValue(PcesConfig_.PERMIT_GAPS, permitGaps)
                .withValue(PcesConfig_.COMPACT_LAST_FILE_ON_STARTUP, false)
                .getOrCreateConfig();

        final Metrics metrics = new NoOpMetrics();

        final var recycleBin = Optional.ofNullable(recycleBinPath)
                .<RecycleBin>map(p -> new RecycleBinImpl(
                        metrics,
                        getStaticThreadManager(),
                        Time.getCurrent(),
                        recycleBinPath,
                        TestRecycleBin.MAXIMUM_FILE_AGE,
                        TestRecycleBin.MINIMUM_PERIOD))
                .orElseGet(TestRecycleBin::getInstance);

        final var builder = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withMetrics(metrics)
                .withTime(Time.getCurrent())
                .withRecycleBin(recycleBin);

        Optional.ofNullable(fsDirectory).ifPresent(builder::withTestFileSystemManagerUnder);

        return builder.build();
    }

    /**
     * Creates a context with a test recycle bin and no-ops metrics.
     * @param dataDir The directory where data is placed
     * @param fsDirectory the directory for the file-system
     * @return a platformContext
     */
    @NonNull
    public static PlatformContext context(final Path dataDir, final Path fsDirectory) {
        return context(false, dataDir, fsDirectory);
    }

    /**
     * Creates a context with a test recycle bin and no-ops metrics.
     * @param permitGaps  Whether gaps are permitted when reading pces files
     * @param dataDir The directory where data is placed
     * @param fsDirectory the directory for the file-system
     * @return a platformContext
     */
    @NonNull
    public static PlatformContext context(final boolean permitGaps, final Path dataDir, final Path fsDirectory) {
        return context(permitGaps, null, dataDir, fsDirectory);
    }
}
