// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl.common;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.base.time.Time;
import com.swirlds.common.io.utility.RecycleBinImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.pces.config.PcesConfig_;
import org.hiero.consensus.test.fixtures.io.TestRecycleBin;

/**
 * A utility class for generating PlatformContexts.
 */
public class PcesTestHelper {

    /**
     * Creates a configuration with the provided data directory.
     *
     * @param dataDir The directory where data is placed
     * @return a platformContext
     */
    @NonNull
    public static Configuration configuration(@NonNull final Path dataDir) {
        return configuration(false, dataDir);
    }

    /**
     * Creates a context.
     *
     * @param permitGaps     Whether gaps are permitted when reading pces files
     * @param dataDir        The directory where data is placed
     * @return a platformContext
     */
    @NonNull
    public static Configuration configuration(final boolean permitGaps, @NonNull final Path dataDir) {
        return new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, dataDir)
                .withValue(PcesConfig_.PREFERRED_FILE_SIZE_MEGABYTES, 5)
                .withValue(PcesConfig_.PERMIT_GAPS, permitGaps)
                .withValue(PcesConfig_.COMPACT_LAST_FILE_ON_STARTUP, false)
                .getOrCreateConfig();
    }

    /**
     * Creates a recycle bin with the provided path.
     *
     * @param recycleBinPath   the path to use for the recycle bin in the context
     * @return a platform context with the provided configuration and recycle bin path
     */
    @NonNull
    public static RecycleBin recycleBin(@Nullable final Path recycleBinPath) {
        return recycleBinPath == null
                ? TestRecycleBin.getInstance()
                : new RecycleBinImpl(
                        new NoOpMetrics(),
                        getStaticThreadManager(),
                        Time.getCurrent(),
                        recycleBinPath,
                        TestRecycleBin.MAXIMUM_FILE_AGE,
                        TestRecycleBin.MINIMUM_PERIOD);
    }
}
